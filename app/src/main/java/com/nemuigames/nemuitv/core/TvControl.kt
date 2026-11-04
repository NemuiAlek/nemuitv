package com.nemuigames.nemuitv.core

import com.nemuigames.nemuitv.config.TvConfig
import com.nemuigames.nemuitv.config.TvInput
import com.nemuigames.nemuitv.ha.HaClient
import com.nemuigames.nemuitv.ha.HaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class TvStatus { READY, HA_OFFLINE, NOT_SET_UP }

/** Input commands for the configured TV, sent through Home Assistant. Fire-and-forget, never queued. */
class TvControl(private val ha: HaClient, private val tv: () -> TvConfig?, scope: CoroutineScope) {

    val status: StateFlow<TvStatus> = combine(ha.status, ha.tvEntity) { haStatus, _ ->
        when {
            tv() == null -> TvStatus.NOT_SET_UP
            haStatus != HaStatus.CONNECTED -> TvStatus.HA_OFFLINE
            else -> TvStatus.READY
        }
    }.stateIn(scope, SharingStarted.Eagerly, if (tv() == null) TvStatus.NOT_SET_UP else TvStatus.HA_OFFLINE)

    fun selectInput(input: TvInput): Boolean = call("select_source") { put("source", input.source) }

    /**
     * Home pressed while the TV shows another input (e.g. the PS5): switch it back to this box. Apps can't send
     * HDMI-CEC "active source", so it goes through HA. Fire-and-forget; HA offline / source unknown → nothing.
     */
    fun switchToThisDevice(): Boolean {
        val cfg = tv() ?: return false
        val thisDevice = cfg.thisDevice
        if (!shouldSwitchToThisDevice(ha.tvState.value?.source, thisDevice)) return false
        val input = cfg.inputs.firstOrNull { it.source == thisDevice } ?: TvInput(thisDevice!!, thisDevice)
        return selectInput(input)
    }

    private fun call(service: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Boolean {
        val entity = tv()?.entity ?: return false
        val data: JsonObject = buildJsonObject { put("entity_id", entity); extra() }
        return ha.callService("media_player", service, data)
    }
}

/** Switch the TV to this box only when HA knows it is on another input (unknown source / no thisDevice → never). */
fun shouldSwitchToThisDevice(source: String?, thisDevice: String?): Boolean =
    source != null && thisDevice != null && source != thisDevice
