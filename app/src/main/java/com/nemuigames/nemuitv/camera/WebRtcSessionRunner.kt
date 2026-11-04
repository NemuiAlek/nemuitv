package com.nemuigames.nemuitv.camera

import android.os.SystemClock
import android.util.Log
import com.nemuigames.nemuitv.ha.HaPort
import com.nemuigames.nemuitv.ha.HaResult
import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.ha.HaSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "NemuiTV"
private const val CONNECT_TIMEOUT_MS = 30_000L // no first frame by then = failed session
private const val DISCONNECT_GRACE_MS = 5_000L // ICE DISCONNECTED this long = session over

/**
 * One live camera session over HA's WebRTC signalling (ported from the M3a spike's SpikeFeed.runSession, which ran
 * 3 Nest feeds for 31 min with 0 reconnects): get_client_config → recv-only audio + video transceivers + data
 * channel → offer subscription → answer / candidates / session id; local candidates buffered until the session id.
 *
 * Must be called on the main dispatcher (CameraFeed's scope): all session logic runs there; WebRTC's threads only
 * post into a channel, except frames, which go straight to [sinksFor] on the decoder thread. Cancellation tears the
 * session down synchronously in `finally` (subscription closed, sink detached, data channel + peer connection —
 * and with it its transceivers — disposed).
 */
class WebRtcSessionRunner(
    private val port: HaPort,
    private val factory: () -> PeerConnectionFactory,
    private val sinksFor: (entityId: String) -> Collection<VideoSink>,
    private val onVideoSize: (entityId: String, width: Int, height: Int) -> Unit,
) : SessionRunner {

    override suspend fun run(entityId: String, onFirstFrame: () -> Unit): SessionEnd {
        val startedAt = SystemClock.elapsedRealtime()
        var reason = "stopped"
        try {
            val end = Session(entityId, onFirstFrame).run()
            reason = when (end) {
                is SessionEnd.Failed -> "failed ${end.code}: ${end.message}"
                is SessionEnd.Dropped -> "dropped (live ${end.liveMs}ms)"
            }
            return end
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reason = "error ${e.javaClass.simpleName}: ${e.message}"
            return SessionEnd.Failed(null, e.message ?: e.javaClass.simpleName)
        } finally {
            // Capped: WebRTC set-description errors can quote SDP lines, which must not reach the log.
            Log.i(TAG, "camera: $entityId ended ${reason.take(120)} after ${SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    /** Everything WebRTC's threads and the helpers report, funnelled onto the session loop (main). */
    private sealed interface Sig {
        data class Local(val c: IceCandidate) : Sig
        data class Ice(val s: PeerConnection.IceConnectionState) : Sig
        data class Remote(val e: SignalEvent) : Sig
        data object SubscriptionClosed : Sig
        data object FirstFrame : Sig
        data class Size(val w: Int, val h: Int) : Sig
        data object ConnectTimeout : Sig
        data object DisconnectGraceOver : Sig
    }

    private inner class Session(val entityId: String, val onFirstFrame: () -> Unit) {
        private val signals = Channel<Sig>(Channel.UNLIMITED)
        private var pc: PeerConnection? = null
        private var sub: HaSubscription? = null
        private var track: VideoTrack? = null
        private var dataChannel: DataChannel? = null
        private var firstFrameAt = 0L // elapsedRealtime of the first frame; 0 = none yet (main thread only)

        /** Forwards every frame to each of the camera's renderers; reports first frame + size changes. */
        private val frameTap = object : VideoSink {
            @Volatile var closed = false
            private var seenFrame = false // decoder thread only
            private var lastW = 0
            private var lastH = 0
            override fun onFrame(frame: VideoFrame) {
                if (closed) return
                val w = frame.rotatedWidth
                val h = frame.rotatedHeight
                if (w != lastW || h != lastH) { lastW = w; lastH = h; signals.trySend(Sig.Size(w, h)) }
                if (!seenFrame) { seenFrame = true; signals.trySend(Sig.FirstFrame) }
                for (sink in sinksFor(entityId)) sink.onFrame(frame)
            }
        }

        suspend fun run(): SessionEnd = try {
            coroutineScope {
                val end = session()
                coroutineContext.cancelChildren() // subscription collector, timers, candidate sends
                end
            }
        } finally {
            teardown()
        }

        /** Spike order: subscription, sink, data channel, peer connection (which disposes its transceivers). */
        private fun teardown() {
            frameTap.closed = true
            signals.close()
            sub?.close(); sub = null
            track?.removeSink(frameTap); track = null
            dataChannel?.dispose(); dataChannel = null
            pc?.dispose(); pc = null
        }

        private fun end(code: String?, message: String?): SessionEnd =
            if (firstFrameAt == 0L) SessionEnd.Failed(code, message)
            else SessionEnd.Dropped(SystemClock.elapsedRealtime() - firstFrameAt)

        private suspend fun CoroutineScope.session(): SessionEnd {
            // Waiting for HA costs no Google quota; CameraFeed's backoff starts only once a real attempt failed.
            if (port.status.value != HaStatus.CONNECTED) port.status.first { it == HaStatus.CONNECTED }

            val cfg = when (val r = port.request("camera/webrtc/get_client_config", buildJsonObject { put("entity_id", entityId) })) {
                is HaResult.Ok -> r.result as? JsonObject
                is HaResult.Error -> return SessionEnd.Failed(r.error.code, r.error.message)
                HaResult.NotConnected -> return SessionEnd.Failed("not_connected", "get_client_config: HA not connected")
                HaResult.Timeout -> return SessionEnd.Failed("timeout", "get_client_config timed out")
            }
            val iceServers = parseIceServers(cfg)
            val dataChannelName = (cfg?.get("dataChannel") as? JsonPrimitive)?.contentOrNull

            val rtc = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val pc = factory().createPeerConnection(rtc, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) { signals.trySend(Sig.Local(c)) }
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { signals.trySend(Sig.Ice(s)) }
                override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
                override fun onAddStream(s: MediaStream) {}
                override fun onRemoveStream(s: MediaStream) {}
                override fun onDataChannel(d: DataChannel) {}
                override fun onRenegotiationNeeded() {}
            }) ?: return SessionEnd.Failed("webrtc", "createPeerConnection returned null")
            this@Session.pc = pc

            // Nest (Google SDM) rejects any offer without "audio, video and application m lines in that order"
            // (verbatim 400 from HA on 11-02), so a recv-only audio line goes first; its track is disabled = silent.
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            ).receiver.track()?.setEnabled(false)
            val video = pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            track = (video.receiver.track() as? VideoTrack)?.also { it.addSink(frameTap) }
            // HA's client config names a data channel for cameras that need one in the offer (Nest: "dataSendChannel").
            if (dataChannelName != null) dataChannel = pc.createDataChannel(dataChannelName, DataChannel.Init())

            val offer = try {
                pc.awaitCreate { o -> createOffer(o, MediaConstraints()) }.also { sdp -> pc.awaitSet { o -> setLocalDescription(o, sdp) } }
            } catch (e: SdpException) {
                return SessionEnd.Failed("webrtc", e.message)
            }

            // The connect clock starts with the offer: every offer costs one Google command.
            launch { delay(CONNECT_TIMEOUT_MS); signals.send(Sig.ConnectTimeout) }
            val sub = port.subscribe("camera/webrtc/offer", offerPayload(entityId, offer.description))
                ?: return SessionEnd.Failed("subscribe_failed", "camera/webrtc/offer refused or HA not connected")
            this@Session.sub = sub
            launch {
                sub.events.collect { signals.send(Sig.Remote(parseSignal(it))) }
                signals.send(Sig.SubscriptionClosed)
            }

            var sessionId: String? = null
            val buffer = CandidateBuffer<SignalEvent.Candidate>()
            var disconnectTimer: Job? = null
            fun send(c: SignalEvent.Candidate) {
                val sid = sessionId ?: return
                launch {
                    val r = port.request("camera/webrtc/candidate", candidatePayload(entityId, sid, c))
                    if (r !is HaResult.Ok) Log.i(TAG, "camera: $entityId candidate send failed: ${r.javaClass.simpleName}")
                }
            }

            for (s in signals) when (s) {
                is Sig.Local -> buffer.add(SignalEvent.Candidate(s.c.sdp, s.c.sdpMid ?: "0", s.c.sdpMLineIndex)).forEach(::send)
                is Sig.Remote -> when (val e = s.e) {
                    is SignalEvent.Session -> { sessionId = e.id; buffer.onSession().forEach(::send) }
                    is SignalEvent.Answer -> try {
                        pc.awaitSet { o -> setRemoteDescription(o, SessionDescription(SessionDescription.Type.ANSWER, e.sdp)) }
                    } catch (x: SdpException) {
                        return end("webrtc", x.message)
                    }
                    is SignalEvent.Candidate -> pc.addIceCandidate(IceCandidate(e.sdpMid, e.sdpMLineIndex, e.candidate))
                    is SignalEvent.Error -> return end(e.code, e.message) // keeps "429"/"RESOURCE_EXHAUSTED" text
                    SignalEvent.Unknown -> {}
                }
                is Sig.Ice -> {
                    disconnectTimer?.cancel(); disconnectTimer = null
                    when (s.s) {
                        PeerConnection.IceConnectionState.FAILED -> return end("ice_failed", "ICE failed")
                        PeerConnection.IceConnectionState.CLOSED -> return end("ice_closed", "ICE closed")
                        PeerConnection.IceConnectionState.DISCONNECTED -> disconnectTimer = launch {
                            delay(DISCONNECT_GRACE_MS); signals.send(Sig.DisconnectGraceOver)
                        }
                        else -> {}
                    }
                }
                Sig.DisconnectGraceOver -> if (disconnectTimer != null) return end("ice_disconnected", "ICE disconnected > 5 s")
                Sig.SubscriptionClosed -> return end("subscription_closed", "offer subscription closed")
                Sig.ConnectTimeout -> if (firstFrameAt == 0L) return SessionEnd.Failed("timeout", "no video within 30 s")
                Sig.FirstFrame -> if (firstFrameAt == 0L) { firstFrameAt = SystemClock.elapsedRealtime(); onFirstFrame() }
                is Sig.Size -> onVideoSize(entityId, s.w, s.h)
            }
            return end("closed", "session channel closed") // not reached: the channel closes only in teardown
        }
    }

    private fun parseIceServers(cfg: JsonObject?): List<PeerConnection.IceServer> {
        val list = (cfg?.get("configuration") as? JsonObject)?.get("iceServers") as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val urls = when (val u = o["urls"]) {
                is JsonPrimitive -> listOfNotNull(u.contentOrNull)
                is JsonArray -> u.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
            if (urls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(urls).apply {
                (o["username"] as? JsonPrimitive)?.contentOrNull?.let { setUsername(it) }
                (o["credential"] as? JsonPrimitive)?.contentOrNull?.let { setPassword(it) }
            }.createIceServer()
        }
    }
}

private class SdpException(message: String) : Exception(message)

private suspend fun PeerConnection.awaitCreate(call: PeerConnection.(SdpObserver) -> Unit): SessionDescription =
    suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resumeWithException(SdpException("createOffer: $e")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        })
    }

private suspend fun PeerConnection.awaitSet(call: PeerConnection.(SdpObserver) -> Unit) =
    suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(SdpException("setDescription: $e")) }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(e: String?) {}
        })
    }
