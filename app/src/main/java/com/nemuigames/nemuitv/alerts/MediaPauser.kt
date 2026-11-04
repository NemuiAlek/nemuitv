package com.nemuigames.nemuitv.alerts

/** A media session we can pause / resume. No Android types here so [MediaPauser] unit-tests on the JVM. */
interface MediaHandle {
    val pkg: String
    val isPlaying: Boolean
    fun pause()
    fun play()
}

/**
 * Pauses the playing session (if any) and later resumes only that same handle. A different session playing at
 * resume time is left alone.
 */
class MediaPauser(private val find: () -> MediaHandle?) {
    private var paused: MediaHandle? = null

    /** True if it paused something (and remembered it). Already holding a paused handle -> false, no second pause. */
    fun pauseIfPlaying(): Boolean {
        if (paused != null) return false
        val h = find()?.takeIf { it.isPlaying } ?: return false
        h.pause()
        paused = h
        return true
    }

    fun resumeIfWePaused() {
        val h = paused ?: return
        paused = null
        h.play()
    }

    /** Clear without resuming (e.g. the user resumed it themselves). */
    fun forget() { paused = null }
}
