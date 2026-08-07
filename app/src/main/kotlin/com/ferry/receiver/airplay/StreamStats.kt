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
    /**
     * The size Ferry **advertised** to the sender — what the resolution setting asked for, not what
     * arrived. e.g. "1920x1080".
     *
     * Kept separate from [videoWidth]/[videoHeight], which are what the decoder is actually
     * producing, because the two can disagree and the disagreement is the whole point: the
     * advertised size is a *request*, and a sender is free to ignore it. The HUD used to show only
     * this one, labelled as if it were the truth — so a 720p setting that the sender declined would
     * still have read "1280x720" on screen, which is precisely the reading that would send someone
     * looking for a picture-quality difference that was never applied.
     */
    @Volatile var videoAdvertised = ""
    @Volatile var videoFps = 0         // frames/sec over the last sample window
    @Volatile var videoQueue = 0       // current decode-queue depth
    @Volatile var videoQueueCapacity = 0  // …out of this many, so "q 15/16" reads as nearly full
    @Volatile var videoDropPct = 0     // cumulative % of frames dropped at the queue, under load

    // ─── Pipeline state, as opposed to tallies ───────────────────────────────
    //
    // Everything above counts events. Counters describe throughput, and are the wrong instrument for
    // a *stall* — when the picture freezes, the interesting fact is that nothing is happening, which
    // a tally can only express by failing to change. Diagnosing the 6.0.0 freeze meant inferring
    // state from which numbers had stopped moving, and that produced two wrong theories before the
    // right one. The fields below report what the pipeline is actually doing.

    /**
     * When a frame last *arrived* and when one was last *shown on screen* (epoch millis, 0 = never).
     *
     * Read together, these two split every freeze in one glance:
     *  - both stale → nothing is arriving; the sender stopped or the connection died,
     *  - arrival fresh but shown stale → frames are coming in and dying inside Ferry.
     *
     * That distinction took three rounds of guessing to establish by hand.
     */
    @Volatile var videoLastArrivalMs = 0L
    @Volatile var videoLastShownMs = 0L

    /**
     * Frames that actually reached the screen.
     *
     * Ferry counted every way a frame could fail — queue drops, decoder drops, render skips — and
     * had no count of success, so "is anything working at all?" was not a question the HUD could
     * answer.
     */
    @Volatile var videoShown = 0

    /** Human-readable decoder state: "ok", "none", "rebuild xN". Blank before a session starts. */
    @Volatile var decoderState = ""

    /**
     * Whether the sender's mirror data connection is currently open.
     *
     * The fact that distinguishes "Ferry is failing to show what it is being sent" from "Ferry is
     * being sent nothing", and the HUD had no way to say it. A frozen picture with the link down is
     * not a decoder problem at all — see `MirrorStreamServer.isStreamDead`.
     */
    @Volatile var videoLinkUp = false

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

    // ─── Watchdog (MirrorStreamServer) ───────────────────────────────────────
    //
    // Surfaced on screen rather than only logged, because the person who needs it cannot read logs:
    // Ferry runs on a TV stick with no adb access, so anything that exists only in logcat may as
    // well not exist. It also means a watchdog that silently saves the session still leaves
    // evidence of what it saved it from — otherwise the fix would hide the bug it is covering.

    /** How many times the watchdog has forced a recovery this session. */
    @Volatile var watchdogRecoveries = 0

    /** Why it fired last, short enough for one HUD line. Blank until it fires. */
    @Volatile var watchdogLastReason = ""

    /** When it last fired (epoch millis, 0 = never), so the HUD can show how long ago. */
    @Volatile var watchdogLastMs = 0L

    // ─── Audio (AudioStreamServer) ───────────────────────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates

    /**
     * True while the audio path is playing marginally fast to drain accumulated latency.
     *
     * On screen because the queue depth beside it is the thing being corrected: seeing `q` fall
     * while this is lit is what shows the controller working, and seeing it lit constantly would
     * mean the queue is being refilled as fast as it drains — a different problem.
     */
    @Volatile var audioCatchUp = false

    /** Clears per-stream counters (call when a mirror session ends). Keeps [overlayEnabled]. */
    fun resetStreams() {
        videoAdvertised = ""; videoFps = 0; videoQueue = 0; videoQueueCapacity = 0; videoDropPct = 0
        videoDecoderDrops = 0; videoKeyframeDrops = 0; videoRenderSkips = 0
        videoLastArrivalMs = 0L; videoLastShownMs = 0L; videoShown = 0; decoderState = ""; videoLinkUp = false
        watchdogRecoveries = 0; watchdogLastReason = ""; watchdogLastMs = 0L
        videoWidth = 0; videoHeight = 0
        audioActive = false; audioQueue = 0; audioDupPct = 0; audioCatchUp = false
        // displayRefreshHz is NOT reset — it is a property of the TV, not of the stream, and
        // StreamingScreen only republishes it when a Surface is created.
    }

    /**
     * How long ago [thenMs] was, as a short string: "0.4s", "12s", "—" if it never happened.
     *
     * Rendered as an age rather than a timestamp because the question being asked is always "is this
     * still happening?", and an age answers it without arithmetic. Sub-10s keeps one decimal, since
     * the difference between 0.1s and 3s is the whole diagnosis; past that the precision is noise.
     * Capped so a long-idle field cannot widen the HUD.
     */
    /**
     * How stale the last arrival must be before the displayed frame rate is shown as 0.
     *
     * Two seconds is comfortably longer than the gap between frames at any real rate, and short
     * enough that a stopped stream reads as stopped rather than as whatever it was last managing.
     */
    private const val FPS_STALE_MS = 2_000L

    /**
     * What the VIDEO line says about resolution: what is actually being decoded, and — only when
     * they disagree — what Ferry asked for.
     *
     * This is how the resolution setting is verified from the couch. The advertised size is a
     * request in the AirPlay `/info` `displays` record, and the sender decides whether to honour it;
     * showing only the request would report success whether or not anything changed. So a 720p
     * setting that took reads `1280x720`, and one the sender declined reads
     * `1920x1080 (asked 1280x720)` — which names the problem instead of hiding it.
     *
     * Falls back to the advertised size before the first frame has been decoded, when it is the only
     * thing known.
     */
    internal fun resolution(): String {
        if (videoWidth <= 0 || videoHeight <= 0) return videoAdvertised.ifEmpty { "—" }
        val actual = "${videoWidth}x$videoHeight"
        return if (videoAdvertised.isEmpty() || videoAdvertised == actual) actual
               else "$actual (asked $videoAdvertised)"
    }

    internal fun ageString(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (thenMs <= 0L) return "—"
        val ms = (nowMs - thenMs).coerceAtLeast(0L)
        return when {
            ms < 10_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
            ms < 100_000 -> "${ms / 1000}s"
            else -> "99s+"
        }
    }

    /**
     * Human-readable multi-line HUD text.
     *
     * Deliberately held to the same five lines it had before the state fields were added — the
     * overlay sits on top of live video on a TV, and the current size was reported as reading well.
     * Anything new therefore has to earn its place by displacing a tally, not by growing the box.
     * The one exception is WATCH, which appears only once the watchdog has actually fired.
     *
     * Reading it for a frozen picture:
     *  - `in` fresh, `last` stale  → frames arriving, dying inside Ferry — look at DEC
     *  - `in` stale, `last` stale  → nothing arriving — the sender or the connection stopped
     *  - `DEC none` or `rebuild`   → there is no decoder to give frames to
     *  - `q 16/16`                 → nothing is draining the queue
     */
    fun summary(): String {
        val now = System.currentTimeMillis()
        val queue = if (videoQueueCapacity > 0) "$videoQueue/$videoQueueCapacity" else "$videoQueue"
        val watch = if (watchdogRecoveries > 0) {
            "\nWATCH  x$watchdogRecoveries  $watchdogLastReason ${ageString(watchdogLastMs, now)} ago"
        } else ""

        // "down" rather than "in" once the sender's data connection has gone, because the two mean
        // completely different things and the HUD previously could not tell them apart: a stale "in"
        // reads as "Ferry is slow", when the truth may be that nothing is being sent at all.
        val arrivalLabel = if (videoLinkUp || videoLastArrivalMs <= 0L) "in" else "down"

        // Displayed fps decays to 0 once arrivals go stale. The stored value is only recomputed
        // every 300 frames, so when the stream stopped it kept displaying whatever rate was last
        // measured — a frozen picture next to a confident "31fps", which is precisely the reading
        // that talked me out of the right diagnosis once already.
        val fps = if (videoLastArrivalMs > 0L && now - videoLastArrivalMs > FPS_STALE_MS) 0 else videoFps

        return "Ferry · debug\n" +
            "VIDEO  ${resolution()}  ${fps}fps  q $queue  $arrivalLabel ${ageString(videoLastArrivalMs, now)}\n" +
            "DEC    ${decoderState.ifEmpty { "—" }}  drops $videoDecoderDrops  kf $videoKeyframeDrops  qdrop ${videoDropPct}%\n" +
            "SHOW   $videoShown  skip $videoRenderSkips  last ${ageString(videoLastShownMs, now)}  ${displayRefreshHz.toInt()}Hz\n" +
            "AUDIO  " + (if (audioActive) {
                "on  q $audioQueue  dup ${audioDupPct}%" + (if (audioCatchUp) "  sync↓" else "")
            } else "off") +
            watch
    }
}
