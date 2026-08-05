package com.ferry.receiver.airplay.handshake

import android.view.Surface
import com.ferry.receiver.airplay.StreamStats
import com.ferry.receiver.airplay.VideoDecoder
import com.ferry.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    private class Config(val sps: ByteArray, val pps: ByteArray) : Item()

    /**
     * @param annexB Annex-B bytes; only the first [length] bytes are valid (the AVCC→Annex-B
     *   conversion runs in place, so the array is the decrypted payload buffer and may have a tail).
     * @param length count of valid bytes in [annexB].
     * @param disposable true when no slice in this frame is used as a reference by any later
     *   frame (every slice NAL has `nal_ref_idc == 0`), so dropping it under load costs a single
     *   invisible frame and needs no keyframe resync.
     * @param keyframe true when the frame carries an IDR slice, so the decoder can resync on it.
     *
     * Both are computed in one pass on the reader thread by [classify], so the decoder thread never
     * walks the frame again.
     */
    private class Frame(
        val annexB: ByteArray,
        val length: Int,
        val disposable: Boolean,
        val keyframe: Boolean,
    ) : Item()

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0)            // OS-assigned free port
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    // Reusable payload read buffer, owned solely by the reader thread. See [readBufferFor].
    private var readBuffer = ByteArray(INITIAL_PAYLOAD_BUFFER)
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and creates a NEW one on return, so we watch for the identity changing
    // and rebuild the decoder — otherwise video stays black after foregrounding.
    @Volatile private var configuredSurface: Surface? = null
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    // Drops that cost a keyframe resync (the expensive kind). Split out from framesDropped because
    // the two have wildly different user impact: a disposable drop is invisible, one of these
    // freezes the picture until iOS next sends an IDR.
    private var keyframeWaits = 0
    // Frames lost at the *decoder* (no free MediaCodec input buffer), as opposed to at the queue.
    // Counted separately because they have a different cause and used to be counted nowhere at all.
    private var decoderDrops = 0
    private var keyframeDrops = 0
    // Type-1 config packets whose SPS/PPS matched the running decoder. Counted only to find out
    // whether this sender pairs parameter sets with IDRs — see configureDecoder.
    private var repeatedConfigs = 0
    // When the current run of skipped-while-resyncing frames began, or 0 if we are not skipping.
    // Decoder thread only — decodeFrame and configureDecoder both run on it, so no volatile needed.
    private var skipStreakStartNs = 0L
    // Earliest time rebuildDecoder may try again, set by discardDecoder after a failure. Decoder
    // thread only, like skipStreakStartNs.
    private var nextRebuildAllowedNs = 0L
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false

    /**
     * Bumped by the reader thread every time it arms [awaitingKeyframe] after shedding a frame that
     * later frames reference.
     *
     * Exists because the decoder thread may now block for up to a keyframe's input-buffer wait
     * (100 ms, see `VideoDecoder.KEYFRAME_INPUT_BUFFER_WAIT_MS`) inside a single `decodeNalUnit`,
     * and the reader
     * keeps running during that window. Without this, the sequence "we were resyncing → an IDR is
     * accepted → clear the flag" could clear an arming the reader raised *while the IDR was in
     * flight*, for a gap that lands after that IDR in stream order — silently reintroducing exactly
     * the corruption this change removes. Comparing the count across the decode call means the flag
     * is only cleared when the resync it belongs to is genuinely the one that just completed.
     *
     * Kept, but no longer load-bearing the way it was. Through 5.5.0 this guard fired routinely,
     * because the keyframe wait was itself long enough to overflow the queue during the IDR's own
     * decode call — so the resync it refused to clear was one that the wait had just caused, and the
     * picture froze for another [RESYNC_GIVE_UP_MS]. [waitBudgetMs] stops the wait spending that
     * headroom, so what reaches this guard now is what it was written for: a real reader-side gap
     * that genuinely does land after this IDR in stream order.
     */
    @Volatile private var resyncArmSeq = 0

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            // Hand the kernel room to hold a burst while the decoder catches up, and don't let
            // Nagle sit on our (small, infrequent) writes back to the sender.
            runCatching {
                socket.receiveBufferSize = SOCKET_RCVBUF
                socket.tcpNoDelay = true
            }.onFailure { Logger.w("Mirror: socket tuning rejected — ${it.message}") }
            // Buffered: every frame costs at least one read for the 128-byte header, and readFully
            // loops for the payload. Unbuffered that is a syscall each time, on a modest SoC, at
            // 60 fps. The buffer is larger than a typical frame so most reads never reach the OS.
            val input = java.io.BufferedInputStream(socket.getInputStream(), READ_BUFFER)
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                // Read into a buffer that is reused across frames. Safe because nothing retains it:
                // type 0 hands it straight to cipher.update(), which returns its own array, and
                // parseConfig copies the SPS/PPS out. It used to be a fresh ByteArray per frame —
                // at 60 fps that was a second large short-lived allocation on top of the decrypted
                // one, on a device with very little GC headroom.
                val payload = readBufferFor(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> {
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        // Decrypt, then rewrite AVCC length prefixes to Annex-B start codes in the
                        // same buffer — both are 4 bytes, so no copy is needed. This used to
                        // allocate a ByteArrayOutputStream and its toByteArray() copy per frame.
                        // Offset/length form: [payload] is the shared buffer and may be longer than
                        // this frame, so the whole-array overload would decrypt stale tail bytes and
                        // desync the CTR keystream for every frame after it.
                        val decrypted = cipher.update(payload, 0, payloadSize)
                        val len = MirrorCrypto.avccToAnnexBInPlace(decrypted)
                        if (len > 0) {
                            val flags = Companion.classify(decrypted, len)
                            enqueue(Frame(
                                decrypted, len,
                                disposable = (flags and FLAG_DISPOSABLE) != 0,
                                keyframe = (flags and FLAG_KEYFRAME) != 0,
                            ))
                        }
                    }
                    1 -> parseConfig(payload, payloadSize)?.let { enqueue(it) }
                    else -> Logger.v { "Mirror: ignoring payload type $payloadType ($payloadSize B)" }
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /**
     * Bounded enqueue. The queue is deliberately shallow (see [QUEUE_CAPACITY]) so it bounds
     * latency rather than absorbing a permanent backlog, which means overflow is a normal event
     * and the *choice of what to drop* is what decides whether the picture survives it.
     *
     * Preference order, cheapest damage first:
     *  1. the incoming frame, if nothing references it — costs one invisible frame;
     *  2. a queued frame nothing references — same, one frame further back;
     *  3. the oldest frame, and resync at the next IDR — the only option left when the sender
     *     marks everything as a reference, and what this always did before.
     *
     * Only case 3 sets [awaitingKeyframe], which is expensive: iOS emits IDRs seconds apart, so
     * every case-3 drop freezes the picture until the next one.
     */
    private fun enqueue(item: Item) {
        framesIn++
        if (!queue.offer(item)) {
            if (item is Frame && item.disposable) {
                framesDropped++                    // 1: refuse the newcomer, break nothing
                StreamStats.videoQueue = queue.size
                return
            }
            val victim = queue.firstOrNull { it is Frame && it.disposable }
            if (victim != null && queue.remove(victim)) {
                framesDropped++                    // 2: evict a frame nothing references
            } else {
                queue.poll()                       // 3: nothing disposable — lose a reference
                framesDropped++
                awaitingKeyframe = true            // reference-broken — resync at the next IDR
                resyncArmSeq++                     // tell the decoder thread this arming is new
                keyframeWaits++
            }
            queue.offer(item)
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            // decoderDrops/keyframeDrops are written on the decoder thread, so read them back
            // through StreamStats' volatile fields rather than the plain ints behind them.
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) keyframeWaits=$keyframeWaits " +
                "decoderDrops=${StreamStats.videoDecoderDrops} " +
                "keyframeDrops=${StreamStats.videoKeyframeDrops} " +
                "queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    /**
     * Returns a buffer of at least [size] bytes for the reader thread to read one payload into.
     *
     * Grows on demand and keeps the larger buffer, except past [MAX_RETAINED_BUFFER]: an outsized
     * frame gets a one-off array that is dropped afterwards rather than pinning megabytes for the
     * rest of the session. [MAX_PAYLOAD] already caps a single frame, but that cap is generous and
     * sender-controlled, and this runs on devices with very little headroom.
     */
    private fun readBufferFor(size: Int): ByteArray {
        if (size <= readBuffer.size) return readBuffer
        val grown = ByteArray(size)
        if (size <= MAX_RETAINED_BUFFER) readBuffer = grown
        return grown
    }

    private fun parseConfig(payload: ByteArray, size: Int): Config? =
        Companion.parseSpsPps(payload, size)?.let { (sps, pps) -> Config(sps, pps) }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    /**
     * The catch is **inside** the loop, and that placement is the whole point.
     *
     * It used to wrap the loop. `VideoDecoder.initialize` can throw for reasons that are entirely
     * transient — `MediaCodec.createDecoderByType` and `configure` both throw unchecked, and
     * `CodecException` is a RuntimeException — so a single bad moment (another app grabbing the
     * hardware decoder, a surface torn down mid-configure) exited this loop for good. `running`
     * stayed true, so the reader kept filling a queue with nobody draining it: every frame dropped,
     * picture frozen, and no way back short of the sender reconnecting. That is a large part of
     * "hung for a very long time".
     *
     * Recovery is to drop the decoder and let [decodeFrame] rebuild it from the cached SPS/PPS on
     * the next frame — nulling [configuredSurface] is what triggers that. [REBUILD_BACKOFF_MS] then
     * rate-limits the retry, so a decoder that keeps failing costs one attempt per second instead of
     * one per frame.
     *
     * There is deliberately no give-up count. These failures are overwhelmingly transient, and a
     * ceiling would only reintroduce the original bug on a longer timer — a session that exhausted
     * it would be just as permanently frozen. The session already has an owner that ends it: the
     * reader thread, when the sender closes the socket.
     */
    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    when (item) {
                        is Config -> configureDecoder(item.sps, item.pps)
                        is Frame -> decodeFrame(item)
                    }
                } catch (e: Exception) {
                    if (!running) break
                    Logger.e("Mirror: decode step failed — dropping decoder, " +
                        "retrying in ${REBUILD_BACKOFF_MS}ms", e)
                    discardDecoder()
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    /**
     * Tears the decoder down so the next frame rebuilds it from the cached SPS/PPS.
     *
     * [lastSps]/[lastPps] are deliberately kept. They describe the stream, not the codec, and they
     * are still valid after a codec failure — requiring a fresh type-1 config packet instead would
     * mean waiting on the sender to volunteer one, which it may never do.
     */
    private fun discardDecoder() {
        runCatching { decoder?.release() }
            .onFailure { Logger.w("Mirror: decoder release during recovery failed — ${it.message}") }
        decoder = null
        configuredSurface = null     // forces decodeFrame to rebuild on the next frame
        awaitingKeyframe = true      // a fresh decoder must start at an IDR
        nextRebuildAllowedNs = System.nanoTime() + REBUILD_BACKOFF_MS * 1_000_000L
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) {
            // Same parameter sets as the running decoder — nothing to rebuild.
            //
            // It is tempting to also arm a keyframe resync here, on the theory that an encoder
            // resends SPS/PPS immediately ahead of an IDR so a receiver that lost sync can rejoin.
            // If that holds, arming is free: the IDR is the very next frame. If it does not — if
            // this sender emits parameter sets on some timer unrelated to IDRs — arming would stop
            // feeding the decoder on a *healthy* stream and freeze the picture until the next IDR,
            // up to a full GOP away. That is a far worse failure than the one being fixed, and
            // which behaviour iOS actually has is not something this code can find out.
            //
            // So: log the pattern, change nothing. If these turn out to arrive paired with IDRs,
            // arming here is a safe follow-up; the log is what settles it.
            repeatedConfigs++
            Logger.i("Mirror: repeated SPS/PPS, no change (count=$repeatedConfigs) — no rebuild")
            return
        }
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface)
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        // Rate-limit retries after a failure. Returning *before* configuredSurface is written is
        // what keeps the retry alive: decodeFrame only rebuilds when the live surface differs from
        // configuredSurface, so recording the surface here would mean never trying again.
        if (System.nanoTime() < nextRebuildAllowedNs) return
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) return                            // backgrounded — wait for the surface to return
        val sc = MirrorCrypto.START_CODE
        decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(frame: Frame) {
        val annexB = frame.annexB
        val length = frame.length
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        }
        val d = decoder ?: return                              // need surface + SPS/PPS first
        if (!d.isHealthy) {
            // MediaCodec error state cannot be cleared by reconfigure — the decoder has to be
            // replaced. Rebuild from the cached SPS/PPS rather than clearing them and waiting for
            // the sender to volunteer a fresh type-1 config packet, which it is under no obligation
            // to send; that wait was itself a way for the picture to stop and never come back.
            Logger.w("Mirror: decoder unhealthy — dropping, rebuilding from cached SPS/PPS")
            discardDecoder()
            return
        }
        // After a reference frame goes missing the stream is reference-broken; skip until the next
        // IDR so we don't feed the decoder predicted frames whose references it never received
        // (which is what smears and morphs the picture).
        var resyncing = awaitingKeyframe
        if (resyncing && !frame.keyframe) {
            // Bounded, so waiting for a keyframe can never become an indefinite freeze.
            //
            // Skipping is the right response to a broken reference chain, but it is only tolerable
            // while recovery is actually coming. If the IDR does not arrive — the sender defers
            // keyframes on a static screen, or something re-arms the resync as fast as it clears —
            // the picture simply stops, with no way back short of restarting the session. That is
            // what 5.0.0 did on real hardware, and it is worse than the corruption it was avoiding.
            //
            // So the skip gives up after [RESYNC_GIVE_UP_MS] and lets frames through again. Brief
            // artifacts until the next IDR, which is where this was headed anyway; the difference
            // is that the screen keeps moving and recovers on its own.
            if (skipStreakStartNs == 0L) skipStreakStartNs = System.nanoTime()
            if (System.nanoTime() - skipStreakStartNs < RESYNC_GIVE_UP_MS * 1_000_000L) return
            Logger.w("Mirror: no keyframe for ${RESYNC_GIVE_UP_MS}ms — resuming decode " +
                     "(brief artifacts expected until the sender's next IDR)")
            awaitingKeyframe = false
            resyncing = false
        }
        skipStreakStartNs = 0L
        // Sampled before the decode call, which can now block: see [resyncArmSeq].
        val armSeqBefore = resyncArmSeq

        // The flag is cleared only AFTER the codec actually accepts the frame, never before.
        // Clearing it up front — which is what this did — meant that if the decoder then dropped
        // this very IDR, the resync was recorded as done while no keyframe had reached the codec.
        // Every predicted frame after it decoded against nothing, and because the flag was already
        // clear, nothing re-armed the resync. That is the corruption that lasted until the *next*
        // IDR, a full GOP later, and it is the bug this ordering fixes.
        // Sampled before the call, because the call is what spends it: the budget is derived from
        // how much queue headroom is left to absorb what the reader enqueues while we block.
        val accepted = d.decodeNalUnit(
            annexB, framePtsUs, length,
            keyframe = frame.keyframe,
            waitBudgetMs = Companion.waitBudgetMs(queue.size),
        )

        if (accepted) {
            if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${length}B)")
            // Only clear if the reader did not arm a *fresh* resync while this frame was in flight.
            // If it did, that gap is later in stream order than this IDR, so we still need another.
            if (resyncing && resyncArmSeq == armSeqBefore) {
                awaitingKeyframe = false
                Logger.i("Mirror: resynced on keyframe after a dropped frame")
            }
            framePtsUs += FRAME_INTERVAL_US
            return
        }

        // Dropped inside the decoder — no free input buffer in time. A second, independent drop
        // point from the one in [enqueue], and one the resync bookkeeping does not cover.
        //
        // 5.0.0 armed a keyframe resync here, reasoning that losing a referenced frame leaves the
        // stream unable to predict correctly. That reasoning is sound and the result was still much
        // worse, so it is deliberately not done any more.
        //
        // Why: arming stops every frame from reaching the decoder until an IDR arrives, and on iOS
        // that is ~10 seconds away. Drops here are not the rare event the queue's drop policy deals
        // with — on a decoder that is merely keeping up, they happen often. So each one bought a
        // ten-second freeze, and the next drop landed moments after recovery. The picture went from
        // occasionally corrupt to showing roughly one frame every ten seconds, which reads as the
        // TV having locked up entirely. Reported from a real device against 5.0.0.
        //
        // The honest trade: a moving picture with transient artifacts beats a still one. Corruption
        // here self-heals at the sender's next IDR either way — the difference is only whether the
        // intervening seconds show something or nothing.
        //
        // The drop is still counted, and [KEYFRAME_INPUT_BUFFER_WAIT_MS] still makes losing an
        // actual IDR far less likely, which is the part that attacks the cause rather than the
        // symptom.
        decoderDrops++
        StreamStats.videoDecoderDrops = decoderDrops
        if (frame.keyframe) {
            keyframeDrops++
            StreamStats.videoKeyframeDrops = keyframeDrops
        }
    }

    /**
     * Waits for the streaming Surface, which MainActivity creates shortly after CONNECTED is
     * emitted. Bounded by [SURFACE_WAIT_TIMEOUT_MS].
     *
     * The poll interval is short on purpose. This sits directly in the path between "sender
     * connected" and "first frame on screen", and the old 100 ms interval added up to a further
     * 100 ms (50 ms on average) of black screen *after* the Surface already existed — pure
     * measurement lag, not work. Polling every 5 ms costs nothing real: the thread has nothing else
     * to do, and it only spins during session start, never in steady state.
     */
    private fun awaitSurface(): Surface? {
        val deadline = System.nanoTime() + SURFACE_WAIT_TIMEOUT_MS * 1_000_000L
        while (running && System.nanoTime() < deadline) {
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_POLL_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame

        /** Starting size of the reusable payload buffer — comfortably above a 1080p frame. */
        private const val INITIAL_PAYLOAD_BUFFER = 512 * 1024
        /** Above this, an outsized frame gets a throwaway array instead of being retained. */
        private const val MAX_RETAINED_BUFFER = 2 * 1024 * 1024
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)

        /**
         * Depth of the reader→decoder handoff, ~267 ms at 60 fps.
         *
         * Was 90 (~1.5 s). On a *live* stream a deep queue is not headroom, it is latency: the
         * sender produces at real time, so a queue that fills during one hiccup never drains and
         * the backlog is permanent for the rest of the session. Shallow keeps the picture close to
         * live and lets [enqueue] shed the occasional frame instead, which is only affordable
         * because that drop now prefers frames nothing references.
         *
         * Kept a little under the audio jitter buffer (AudioStreamServer.AUDIO_QUEUE_CAPACITY,
         * ~350 ms) on purpose — a late video frame is invisible, an audio underrun is an audible
         * crackle, so audio gets the deeper cushion.
         */
        private const val QUEUE_CAPACITY = 16

        /**
         * How long the decoder will skip frames waiting for a keyframe before giving up and
         * decoding whatever arrives.
         *
         * A ceiling on how long the picture may sit still. Generous enough that a resync with an
         * IDR genuinely on the way still completes cleanly, short enough that a resync which is
         * never going to complete does not read as a crashed TV. Without it, "wait for a keyframe"
         * has no upper bound at all, and on iOS the next IDR can be ten seconds out or, on a static
         * screen, considerably more.
         */
        private const val RESYNC_GIVE_UP_MS = 3_000L

        /**
         * Minimum gap between decoder rebuild attempts after a failure.
         *
         * Long enough that a codec failing every time costs one attempt per second rather than one
         * per frame (building a MediaCodec is not cheap, and the usual cause — another app holding
         * the hardware decoder — needs wall-clock time to clear, not retries). Short enough that a
         * transient failure costs about a second of picture rather than the session.
         */
        private const val REBUILD_BACKOFF_MS = 1_000L

        /**
         * Frames of queue headroom the decoder thread is never allowed to spend waiting.
         *
         * The budget below is an estimate built on a nominal 60 fps reader; this absorbs the error
         * in that estimate, plus the frames the reader adds between [waitBudgetMs] sampling the
         * depth and the wait actually beginning.
         */
        private const val WAIT_BUDGET_RESERVE_FRAMES = 4

        /** Nominal reader cadence — one frame per interval at 60 fps. */
        private const val FRAME_INTERVAL_MS = 16L

        /**
         * How long the decoder thread may block inside a single `decodeNalUnit`, given how full the
         * queue already is.
         *
         * This is the guard 5.5.0 assumed it had. `decodeNalUnit` blocks waiting for a free codec
         * input buffer, and it does so on the *only* thread that drains [queue] — so while it waits,
         * the reader keeps enqueuing and nothing is removed. Wait long enough and the queue
         * overflows; [enqueue] then has to drop a referenced frame, which arms [awaitingKeyframe]
         * and stops the picture for up to [RESYNC_GIVE_UP_MS]. The wait meant to save one frame
         * costs three seconds of them.
         *
         * So the wait is capped at the time the remaining headroom can actually cover:
         * `(headroom - reserve) x frame interval`. Empty queue → the full ceiling (192 ms of budget
         * against a 100 ms keyframe ceiling, so the common case is exactly what 5.5.0 intended).
         * Queue within [WAIT_BUDGET_RESERVE_FRAMES] of full → zero, drop immediately, because at
         * that depth there is nothing left to spend and frames are already piling up behind this one.
         *
         * Deliberately a pure function of depth, in the companion and `internal`, so the policy is
         * unit-testable without a socket or a codec.
         */
        internal fun waitBudgetMs(queueDepth: Int, capacity: Int = QUEUE_CAPACITY): Long {
            val headroom = capacity - queueDepth - WAIT_BUDGET_RESERVE_FRAMES
            return if (headroom <= 0) 0L else headroom * FRAME_INTERVAL_MS
        }

        private const val READ_BUFFER = 256 * 1024             // > one frame, so most reads miss the OS
        private const val SOCKET_RCVBUF = 1024 * 1024          // kernel-side burst absorption
        private const val NAL_SLICE_NON_IDR = 1
        private const val NAL_SLICE_IDR = 5
        /** Overall ceiling on waiting for the Surface — unchanged; only the granularity improved. */
        private const val SURFACE_WAIT_TIMEOUT_MS = 5_000L
        private const val SURFACE_POLL_MS = 5L

        /**
         * Extracts (SPS, PPS) from a type-1 config payload, or null if it is malformed.
         *
         * [size] is the payload's real length, which is **not** `payload.size` — the reader reuses
         * one buffer and it is usually longer than the frame in it. Every offset is therefore
         * checked against [size] rather than against the array bounds: a hostile or corrupt length
         * field that points past the frame but inside the buffer would otherwise read stale bytes
         * from an earlier frame without throwing, and the decoder would be configured from them.
         *
         * In the companion, and `internal`, so it is testable without opening a socket.
         */
        internal fun parseSpsPps(payload: ByteArray, size: Int): Pair<ByteArray, ByteArray>? = try {
            require(size >= 8) { "config payload too short: $size" }
            val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
            require(spsSize > 0 && 8 + spsSize <= size) { "bad spsSize=$spsSize (payload $size)" }
            val sps = payload.copyOfRange(8, 8 + spsSize)
            val ppsLenOffset = 8 + spsSize + 1               // skip the 1-byte PPS count
            require(ppsLenOffset + 2 <= size) { "pps length field past end (payload $size)" }
            val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
                (payload[ppsLenOffset + 1].toInt() and 0xFF)
            require(ppsSize > 0 && ppsLenOffset + 2 + ppsSize <= size) {
                "bad ppsSize=$ppsSize (payload $size)"
            }
            sps to payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize)
        } catch (e: Exception) {
            Logger.e("Mirror: failed to parse SPS/PPS", e); null
        }

        /**
         * True if no later frame can reference this one, so dropping it under load is invisible
         * and needs no keyframe resync.
         *
         * The NAL header byte is `forbidden_zero(1) | nal_ref_idc(2) | nal_unit_type(5)`. A slice
         * with `nal_ref_idc == 0` is not retained as a reference picture (H.264 §7.4.1). A frame
         * is safe to drop only if *every* slice in it is non-reference — one referenced slice and
         * later frames still predict from it.
         *
         * Conservative by construction: a frame carrying no slice NALs at all returns false, and a
         * sender that marks everything as a reference (some low-delay encoders do) simply never
         * produces a true here, leaving the old drop-oldest-and-resync behaviour in place.
         *
         * In the companion, and `internal`, so it is testable without opening a socket.
         */
        internal fun isDisposable(annexB: ByteArray, length: Int = annexB.size): Boolean =
            (classify(annexB, length) and FLAG_DISPOSABLE) != 0

        /** True if the frame carries an IDR slice — a point the decoder can resync on. */
        internal fun isKeyframe(annexB: ByteArray, length: Int = annexB.size): Boolean =
            (classify(annexB, length) and FLAG_KEYFRAME) != 0

        const val FLAG_DISPOSABLE = 1
        const val FLAG_KEYFRAME = 2

        /**
         * Single walk of a frame's NAL headers, returning [FLAG_DISPOSABLE] and [FLAG_KEYFRAME].
         *
         * Both properties are decided by the same 1-byte NAL headers, so answering them together
         * costs one pass instead of two. Disposability is needed for every frame (the drop policy
         * reads it at enqueue) and keyframe-ness whenever the decoder is resyncing; computing both
         * up front is never more work than the old two-function version and is half the work in the
         * resync case, which is exactly when the pipeline is already struggling.
         *
         * In the companion, and `internal`, so it is testable without opening a socket.
         */
        internal fun classify(annexB: ByteArray, length: Int = annexB.size): Int {
            var sawSlice = false
            var referenced = false
            var keyframe = false
            var i = 0
            while (i + 3 < length) {
                if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                    val header = annexB[i + 3].toInt()
                    val type = header and 0x1F
                    if (type == NAL_SLICE_IDR || type == NAL_SLICE_NON_IDR) {
                        sawSlice = true
                        if (type == NAL_SLICE_IDR) keyframe = true
                        // nal_ref_idc != 0 → a later frame may predict from this slice.
                        if ((header and 0x60) != 0) {
                            referenced = true
                            // Both answers are settled, so stop walking the payload.
                            //
                            // Disposability is decided: one referenced slice is enough. Keyframe-ness
                            // is decided too, because H.264 does not mix IDR and non-IDR slices
                            // within one access unit (§7.4.1.2.4) — so whatever the slices seen so
                            // far say, the rest of the frame says the same. An IDR slice always has
                            // nal_ref_idc != 0, so an IDR frame breaks here with keyframe already set.
                            //
                            // This early exit is what the old isDisposable did, and it matters: a
                            // typical single-slice frame settles after 4 bytes instead of a walk over
                            // ~100 KB of payload looking for start codes.
                            break
                        }
                    }
                    i += 4
                } else {
                    i++
                }
            }
            var flags = 0
            // Conservative by construction: a frame carrying no slice NALs at all is not disposable,
            // and a sender that marks everything as a reference simply never yields this flag.
            if (sawSlice && !referenced) flags = flags or FLAG_DISPOSABLE
            if (keyframe) flags = flags or FLAG_KEYFRAME
            return flags
        }
    }
}
