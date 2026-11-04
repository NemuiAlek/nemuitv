package com.nemuigames.nemuitv.nowplaying

/**
 * Which artwork is cached: the source bitmap object from the session's latest metadata ([src], compared by identity —
 * `Bitmap` doesn't override `equals`). Not the track's text: Spotify sends the new track's text first (with no art or
 * the previous track's art) and the new artwork in a later update with the same text, so a text key kept the stale
 * art (couch test 2). A metadata object hands out the same bitmap every time it's read, so refreshes from playback
 * changes are still hits; only a new metadata update (a new bitmap) is scaled again.
 */
data class ArtKey(val pkg: String, val src: Any?)

/**
 * Remembers one value: [get] with the same (equal) key returns it again without calling [make]. Null values count.
 * [same]: when a new key makes a value equal to the cached one (e.g. the same artwork re-sent in a new bitmap), the
 * cached value is kept and returned, so equal states stay equal (identical objects) and nothing re-emits.
 */
class SingleSlotCache<K : Any, V>(private val same: (V & Any, V & Any) -> Boolean = { _, _ -> false }) {
    private var key: K? = null
    private var value: V? = null

    @Suppress("UNCHECKED_CAST")
    fun get(key: K, make: () -> V): V {
        if (key == this.key) return value as V
        val v = make()
        val old = value
        this.key = key
        if (v != null && old != null && same(v, old)) return old
        this.value = v
        return v
    }
}

/** Which track a metadata update is for: same app + title + artist = the same track. */
data class TrackKey(val pkg: String, val title: String?, val artist: String?)

/**
 * Picks the source bitmap to show for each metadata update. [cover] = ART / ALBUM_ART, [icon] = DISPLAY_ICON.
 * - A cover is always used (keyed on the bitmap downstream, so Spotify's "new text with the old cover, then the new
 *   cover with the same text" still ends on the new cover — couch test 2).
 * - Same track, no cover: keep the cover last shown for it. Spotify sends ~6 updates per play/pause, some with only a
 *   DISPLAY_ICON; falling back to it made the art flicker cover → icon → cover (couch round 1, 11-02).
 * - New track, no cover: [icon] (the caller falls back to the app icon after that).
 * B is the bitmap type (generic so tests don't need android.graphics.Bitmap).
 */
class ArtSource<B : Any> {
    private var track: TrackKey? = null
    private var cover: B? = null

    fun choose(track: TrackKey, cover: B?, icon: B?): B? {
        if (cover != null) {
            this.track = track; this.cover = cover
            return cover
        }
        if (track == this.track && this.cover != null) return this.cover
        this.track = track; this.cover = null
        return icon
    }
}
