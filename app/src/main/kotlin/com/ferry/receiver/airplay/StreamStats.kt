package com.ferry.receiver.airplay

/**
 * StreamStats — live counters for the optional on-screen debug overlay (Settings → "Debug overlay").
 *
 * The mirror video server ([com.ferry.receiver.airplay.handshake.MirrorStreamServer]) and audio server
 * ([com.ferry.receiver.airplay.handshake.AudioStreamServer]) write these volatile fields as they run;
 * [com.ferry.receiver.ui.StreamingScreen] polls [summary] a few times a second to render the HUD. Plain
 * volatile ints keep the hot path allocation-free — no flows or locks on the per-frame path.
 *
 * [overlayEnabled] is set from [com.ferry.receiver.settings.AppSettings.showDebugOverlay] when the AirPlay
 * receiver starts, so the HUD appears for the next mirroring session after the toggle is flipped.
 */
object StreamStats {
    /** Master switch, mirrored from the user setting; the overlay only draws when true. */
    @Volatile var overlayEnabled = false

    /**
     * Mirrored from [com.ferry.receiver.settings.AppSettings.smartFillEnabled]. When true,
     * [com.ferry.receiver.ui.StreamingScreen] fills the TV and crops up to
     * [com.ferry.receiver.util.VideoFit.MAX_CROP_FRACTION] rather than showing black bars.
     *
     * Read on the layout tick rather than at session start, so flipping the toggle takes effect on
     * the picture immediately instead of at the next connection.
     */
    @Volatile var smartFillEnabled = true

    /**
     * Mirrored from [com.ferry.receiver.settings.AppSettings.audioBoostDb] — extra playback gain in
     * dB, 0 when off.
     *
     * Both audio paths re-read this as they play (via
     * [com.ferry.receiver.util.LoudnessBoost.sync], which is a no-op unless the value changed), so
     * changing the setting affects audio that is already playing rather than only the next session.
     * Same reasoning as [smartFillEnabled]: a setting whose whole purpose is "does this sound
     * better?" has to be judgeable while listening to it.
     */
    @Volatile var audioBoostDb = 0

    // ─── Video (MirrorStreamServer) ──────────────────────────────────────────
    @Volatile var videoRes = ""        // e.g. "1920x1080"
    @Volatile var videoFps = 0         // frames/sec over the last sample window
    @Volatile var videoQueue = 0       // current decode-queue depth
    @Volatile var videoDropPct = 0     // cumulative % of frames dropped at the queue, under load

    /**
     * Frames dropped *inside* the decoder because no MediaCodec input buffer came free in time —
     * a different failure from [videoDropPct], which counts frames shed at the queue.
     *
     * Split out because the two look identical on screen but have different causes, and this one
     * used to be invisible: it was logged at verbose and counted nowhere. If the picture corrupts
     * while queue drop% stays flat, this is the counter that moves.
     */
    @Volatile var videoDecoderDrops = 0

    /** Of those, the expensive ones: a dropped IDR costs a full GOP of corrupt picture. */
    @Volatile var videoKeyframeDrops = 0

    /**
     * Frames that were decoded normally but deliberately not shown, because the display had not yet
     * consumed the previous one (see `VideoDecoder.shouldRender`).
     *
     * Deliberately sits next to [videoDecoderDrops] in the HUD, because reading the two together is
     * the whole point: this counter going up while that one stays flat is the fix working. A skip
     * here costs a single invisible frame; a drop there costs every frame until the sender's next
     * IDR. Trading the second for the first is the entire change in 6.5.0.
     */
    @Volatile var videoRenderSkips = 0

    /**
     * The panel's refresh rate in Hz, published by [com.ferry.receiver.ui.StreamingScreen] once its
     * Surface exists, because that is the only place with a [android.view.Display] to ask.
     *
     * [com.ferry.receiver.airplay.VideoDecoder] paces rendering against this. 60 is the default
     * rather than 0 so the pacing is sane before the real value arrives (and if it never does).
     */
    @Volatile var displayRefreshHz = 60f

    // Actual decoded video dimensions (from the SPS, so portrait phone streams are portrait here).
    // StreamingScreen reads these to aspect-fit the Surface instead of stretching to 16:9.
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0

    // ─── Audio (AudioStreamServer) ───────────────────────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates

    /** Clears per-stream counters (call when a mirror session ends). Keeps [overlayEnabled]. */
    fun resetStreams() {
        videoRes = ""; videoFps = 0; videoQueue = 0; videoDropPct = 0
        videoDecoderDrops = 0; videoKeyframeDrops = 0; videoRenderSkips = 0
        videoWidth = 0; videoHeight = 0
        audioActive = false; audioQueue = 0; audioDupPct = 0
        // displayRefreshHz is NOT reset — it is a property of the TV, not of the stream, and
        // StreamingScreen only republishes it when a Surface is created.
    }

    /** Human-readable multi-line HUD text. */
    fun summary(): String =
        "Ferry · debug\n" +
        "VIDEO  ${videoRes.ifEmpty { "—" }}   ${videoFps} fps   q ${videoQueue}   drop ${videoDropPct}%\n" +
        "DEC    dropped ${videoDecoderDrops}   keyframes lost ${videoKeyframeDrops}\n" +
        "SHOW   skipped ${videoRenderSkips}   panel ${displayRefreshHz.toInt()}Hz\n" +
        "AUDIO  " + (if (audioActive) "on   q ${audioQueue}   dup ${audioDupPct}%" else "off")
}
