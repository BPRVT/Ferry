package com.ferry.receiver.airplay.handshake

import android.view.Surface
import com.ferry.receiver.airplay.StreamStats
import com.ferry.receiver.airplay.VideoDecoder
import com.ferry.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
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
    /**
     * Called once when the video half of the session is dead beyond recovery — see [isStreamDead].
     * The receiver ends the RTSP session so the sender stops believing it is still mirroring and
     * re-establishes properly, with a fresh SETUP and fresh keys.
     */
    private val onStreamDead: () -> Unit = {},
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

    /**
     * The video path's own three threads, rather than `Dispatchers.IO`.
     *
     * Two reasons, and the first is the one that matters.
     *
     * **Isolation.** `Dispatchers.IO` is a shared pool capped at 64 threads, and all three loops here
     * spend their lives inside blocking socket calls. Anything else in the process that parks an IO
     * thread and never returns — a leaked server, a socket nobody closed — permanently removes
     * capacity from the same pool the video reader and decoder are drawn from. Once enough has
     * accumulated, starting a cast means waiting for a thread that is never coming back, and the
     * picture stutters for reasons that have nothing to do with video. `AirPlayReceiver`'s
     * `reclaimMirrorSessionResources` fixes the leaks that were doing this; owning the threads means
     * the *next* such oversight costs a bounded pool that dies with [stop] instead of shared capacity
     * that does not.
     *
     * **Priority.** These are display-deadline threads. On a shared pool their priority cannot be
     * raised without leaking that priority to whatever unrelated work runs there next; on their own
     * it is simply correct, and it is what keeps the decoder scheduled ahead of background work when
     * the SoC is busy — which on a stick is most of the time.
     */
    private val threads = Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
        Thread({
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            runnable.run()
        }, "FerryMirror").apply { isDaemon = true }
    }
    private val dispatcher = threads.asCoroutineDispatcher()

    /**
     * Recycled buffers for decrypted frames — the largest allocation on the whole video path.
     *
     * `cipher.update(payload, 0, size)` returns a **freshly allocated array per frame**. At 1080p60
     * that is roughly 60 arrays a second of tens to hundreds of kilobytes each: several megabytes per
     * second of short-lived garbage, on a device whose heap has very little headroom. The cost is not
     * the allocation, it is the collection — every GC steals CPU from the decoder thread, and the
     * decoder thread missing its deadline is precisely what [decodeNalUnit] turns into a dropped
     * frame and a corrupt picture. The reader already reuses its *encrypted* read buffer for exactly
     * this reason ([readBufferFor]); the decrypted side was still allocating.
     *
     * It cannot be a single reused buffer, because a decrypted frame is handed to the decoder thread
     * and outlives the reader's next iteration — hence a pool. A buffer is taken here, filled by the
     * reader, and returned once the decoder has copied it into a MediaCodec input buffer (or once it
     * is dropped, at either drop site). Running dry is not an error: it allocates, exactly as before.
     *
     * Thread-safe because both the reader (frames dropped at [enqueue]) and the decoder thread return
     * buffers to it.
     */
    private val bufferPool = ArrayBlockingQueue<ByteArray>(FRAME_POOL_CAPACITY)

    /**
     * Size the pool's buffers are cut to — the largest frame seen so far, so a keyframe does not have
     * to allocate. Only ever grows, and only the reader thread touches it.
     */
    private var frameBufferSize = INITIAL_PAYLOAD_BUFFER

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
    /** Volatile: written by the reader thread, sampled by the watchdog to measure loss over time. */
    @Volatile private var framesIn = 0
    /** Volatile for the same reason as [framesIn] — the watchdog samples it to measure loss. */
    @Volatile private var framesDropped = 0
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
    // thread only, like skipStreakStartNs — except for the watchdog, which clears it under
    // [forceRebuild]; a stale read there can only cost one extra tick.
    private var nextRebuildAllowedNs = 0L

    /** Set by the watchdog, acted on by the decoder thread. See [runWatchdog]. */
    @Volatile private var forceRebuild = false

    /**
     * Set by the watchdog when the Surface has gone and the decoder should be handed back; acted on
     * by the decoder thread, which is the only thread allowed to touch [decoder]. See [runWatchdog].
     */
    @Volatile private var releaseIdleDecoder = false

    /**
     * When the first frame of this stream arrived, for [isStalled] to measure against when nothing
     * has ever been shown. Written once by the reader thread.
     */
    @Volatile private var firstArrivalMs = 0L

    /** Rebuild attempts this session, for the HUD's decoder state. */
    @Volatile private var decoderRebuilds = 0

    /**
     * State of the sender's data connection. Written by the reader thread, read by the watchdog.
     *
     * [everConnected] distinguishes "the sender has not connected yet", where being disconnected is
     * the normal starting state, from "the sender connected and then went away", which is a fault.
     */
    @Volatile private var dataConnected = false
    @Volatile private var everConnected = false
    @Volatile private var dataClosedAtMs = 0L

    /** So the session is only declared dead once, no matter how long the watchdog keeps ticking. */
    @Volatile private var streamDeathReported = false

    // ─── Sustained-degradation escalation (watchdog thread only) ─────────────────────────────
    //
    // Reported from hardware, and the observation that shaped this: **stopping and restarting the
    // share acts like a fresh slate.** That is the single most informative thing anyone has said
    // about this failure, because of what it rules out. If restarting the *session* fixes it, the
    // thing that went wrong is inside the session — it is not the network degrading, not the SoC
    // throttling, and not anything that would survive a fresh SETUP. And Ferry already knows how to
    // do exactly what the user was doing by hand: ending the RTSP session makes the sender
    // re-establish it, with fresh keys, a fresh decoder and an empty pipeline.
    //
    // So it does it itself now, rather than requiring somebody to notice and reach for the iPad.
    //
    // The bar is deliberately high, because a false positive tears down a working cast. Loss is
    // measured over whole seconds and has to stay bad; the response escalates from the cheap remedy
    // to the expensive one; and a recycle is rate-limited so a genuinely bad link degrades into
    // "poor picture" rather than "a session that restarts every ten seconds", which would be worse
    // than the problem.
    private var lastFramesIn = 0
    private var lastFramesShown = 0
    /** Consecutive watchdog rebuilds that did not put a single frame on screen. */
    private var consecutiveStallRebuilds = 0
    private var lastFramesLost = 0
    private var degradedTicks = 0
    private var lastRecycleMs = 0L
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
        StreamStats.videoQueueCapacity = QUEUE_CAPACITY
        publishDecoderState()
        scope.launch(dispatcher) { runReader() }
        scope.launch(dispatcher) { runDecoder() }
        scope.launch(dispatcher) { runWatchdog() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        bufferPool.clear()
        // shutdown(), never shutdownNow(). The three loops all exit on `running` within one poll
        // timeout, and the decoder thread's `finally` has to reach `decoder.release()` on its way
        // out — interrupting it mid-release is how you get a native SIGABRT out of libstagefright
        // (the same hazard AudioStreamServer.stop documents). The pool terminates on its own once
        // they return, and the threads are daemons, so nothing can hold the process open either way.
        runCatching { threads.shutdown() }
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            dataConnected = true
            everConnected = true
            StreamStats.videoLinkUp = true
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
                        // Decrypt INTO a recycled buffer rather than letting the cipher allocate a
                        // fresh array per frame — see [bufferPool]. The buffer is usually longer
                        // than the frame, which is why every step below is given an explicit
                        // length: an in-place conversion or a NAL walk bounded by `array.size`
                        // would read the tail of an *older* frame still sitting in the buffer.
                        val decrypted = acquireFrameBuffer(payloadSize)
                        val plainLen = cipher.update(payload, 0, payloadSize, decrypted, 0)
                        val len = MirrorCrypto.avccToAnnexBInPlace(decrypted, plainLen)
                        if (len > 0) {
                            val flags = Companion.classify(decrypted, len)
                            enqueue(Frame(
                                decrypted, len,
                                disposable = (flags and FLAG_DISPOSABLE) != 0,
                                keyframe = (flags and FLAG_KEYFRAME) != 0,
                            ))
                        } else {
                            recycleFrameBuffer(decrypted)
                        }
                    }
                    1 -> parseConfig(payload, payloadSize)?.let { enqueue(it) }
                    else -> Logger.v { "Mirror: ignoring payload type $payloadType ($payloadSize B)" }
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            // NOT `running = false`. That single line is why a dropped video connection used to
            // freeze the picture forever: it also terminated the decoder thread and the watchdog, so
            // the one part of Ferry that could have noticed and reacted was killed by the event it
            // needed to react to. `running` means "this server is shutting down" and only [stop]
            // gets to say that; a sender going away is a different fact, recorded below.
            //
            // No second accept() either, deliberately. The AES-CTR keystream is bound to this data
            // connection, so a sender reconnecting to the same socket would decrypt to garbage —
            // visibly worse than the freeze. A genuine reconnect has to come through a fresh SETUP,
            // which builds a new MirrorStreamServer with new keys, and that is what ending the
            // session below asks the sender to do.
            dataConnected = false
            dataClosedAtMs = System.currentTimeMillis()
            StreamStats.videoLinkUp = false
            Logger.w("Mirror data connection ended — video is dead until the session is re-established")
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
        // Timestamp every arrival, not every 300th like the fps sample below. The watchdog and the
        // HUD both need "is anything still coming in *right now*", which a sampled counter cannot
        // answer — that was exactly the ambiguity that made a frozen picture hard to read.
        val arrivedMs = System.currentTimeMillis()
        StreamStats.videoLastArrivalMs = arrivedMs
        if (firstArrivalMs == 0L) firstArrivalMs = arrivedMs
        if (!queue.offer(item)) {
            // Every branch below destroys a frame, and each one is the last reference to that
            // frame's buffer — so each one also returns it to the pool. Missing any of them would
            // drain the pool exactly when the pipeline is under load and least able to absorb the
            // allocations, which is the opposite of what it is for.
            if (item is Frame && item.disposable) {
                framesDropped++                    // 1: refuse the newcomer, break nothing
                recycleFrameBuffer(item.annexB)
                StreamStats.videoQueue = queue.size
                return
            }
            val victim = queue.firstOrNull { it is Frame && it.disposable }
            if (victim != null && queue.remove(victim)) {
                framesDropped++                    // 2: evict a frame nothing references
                recycleFrameBuffer((victim as Frame).annexB)
            } else {
                val evicted = queue.poll()         // 3: nothing disposable — lose a reference
                (evicted as? Frame)?.let { recycleFrameBuffer(it.annexB) }
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
            // Long arithmetic: `framesDropped * 100` overflows Int past ~21M dropped frames, which a
            // long-running session can reach, and the percentage then goes negative.
            StreamStats.videoDropPct = (framesDropped.toLong() * 100 / framesIn).toInt()
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

    /**
     * Takes a buffer of at least [size] bytes from [bufferPool] for one decrypted frame.
     *
     * Buffers are cut to [frameBufferSize] — the largest frame seen so far — rather than to the
     * frame in hand, so the pool converges on a single size and a keyframe (much larger than the
     * predicted frames around it) does not force an allocation every time one arrives. Pooled
     * buffers cut before the last growth are simply discarded as they come up, which settles within
     * one pass of the pool.
     *
     * Reader thread only; [frameBufferSize] needs no synchronisation because of it.
     */
    private fun acquireFrameBuffer(size: Int): ByteArray {
        // An outsized frame gets a one-off array and does NOT resize the pool, which is the whole
        // point of the cap. Letting [frameBufferSize] track the largest frame ever seen without a
        // ceiling means one freak 2 MB frame permanently re-cuts every buffer in the pool to 2 MB —
        // [FRAME_POOL_CAPACITY] × that, held for the rest of the session, on a device with very
        // little to spare. Same rule [readBufferFor] applies to the encrypted side, and the reason
        // that one has it.
        if (size > POOLED_FRAME_LIMIT) return ByteArray(size)
        if (size > frameBufferSize) frameBufferSize = size
        while (true) {
            val pooled = bufferPool.poll() ?: break
            if (pooled.size >= frameBufferSize) return pooled
        }
        return ByteArray(frameBufferSize)
    }

    /**
     * Returns a decrypted-frame buffer for reuse, once nothing can still be reading it.
     *
     * Outsized buffers are dropped rather than pooled, for the same reason [readBufferFor] refuses
     * to retain them: one freak frame should not pin megabytes for the rest of the session. The
     * offer is allowed to fail — a full pool means we already have all the buffers we need.
     */
    private fun recycleFrameBuffer(buffer: ByteArray) {
        if (buffer.size > POOLED_FRAME_LIMIT) return
        bufferPool.offer(buffer)
    }

    private fun parseConfig(payload: ByteArray, size: Int): Config? =
        Companion.parseSpsPps(payload, size)?.let { (sps, pps) -> Config(sps, pps) }

    /**
     * Watchdog thread: notice when the picture has stopped moving, and force the video path to
     * rebuild itself.
     *
     * Exists because a frozen picture used to be **permanent**. Reported from hardware on 6.0.0: the
     * iPad kept playing, TV audio kept playing, and the video sat on one frame until the cast was
     * stopped and restarted by hand. Audio survives because it is a separate server on a separate
     * socket, and nothing in the video path was watching itself — a decoder that stopped producing
     * simply stayed stopped, with the session still reporting CONNECTED to the UI and to the sender.
     *
     * It deliberately does not tear the session down, only rebuild the decoder. A rebuild is cheap
     * and recoverable; dropping a live session on a false positive is not, and the detector is new.
     * If a rebuild does not fix it, the HUD says so — which is the honest outcome, since it means
     * the fault is somewhere this cannot reach.
     *
     * The recovery is requested through [forceRebuild] rather than performed here: [decoder] belongs
     * to the decoder thread, and reaching into it from this one would race a rebuild against a
     * decode. That is safe because none of the decoder thread's waits is unbounded, so it always
     * comes back to check the flag.
     */
    private suspend fun runWatchdog() {
        while (running) {
            kotlinx.coroutines.delay(WATCHDOG_INTERVAL_MS)
            if (!running) break
            val now = System.currentTimeMillis()

            // Rule 1: the video connection is gone. Rebuilding a decoder cannot help — there is
            // nothing to decode — so end the session instead and let the sender re-establish it.
            // This is the failure that was reported: iPad still playing, TV audio still playing,
            // picture frozen on one frame, and Ferry still reporting CONNECTED to everyone.
            if (Companion.isStreamDead(now, everConnected, dataConnected, dataClosedAtMs)) {
                if (!streamDeathReported) {
                    streamDeathReported = true
                    StreamStats.watchdogRecoveries++
                    StreamStats.watchdogLastReason = "video link lost"
                    StreamStats.watchdogLastMs = now
                    Logger.w("Watchdog: mirror data connection is gone — ending the session so the " +
                        "sender re-establishes it")
                    onStreamDead()
                }
                continue
            }

            // Rule 1a: both halves of the session have gone quiet while the socket is still open.
            //
            // **This is the failure that was captured on hardware.** The picture froze, the iPad went
            // on playing, and Ferry sat there for twelve seconds until the user gave up and closed
            // it. Neither existing rule could fire: the data socket never closed, so [isStreamDead]
            // saw nothing wrong, and [isStalled] deliberately refuses to judge when no frames are
            // arriving — because iOS sends video only on change, and a paused iPad is indistinguishable
            // from a dead link if video is all you look at.
            //
            // Audio breaks that tie, and it is the signal Ferry has always had and never used.
            // Realtime mirroring audio is not event-driven: it runs at a constant ~92 packets a
            // second for as long as the session lives. In the captured log both streams stopped
            // within a second of each other and never came back. A realtime stream that goes silent
            // is not idling — it is gone.
            if (Companion.isSessionSilent(now, StreamStats.audioLastArrivalMs, StreamStats.videoLastArrivalMs)) {
                if (!streamDeathReported) {
                    streamDeathReported = true
                    StreamStats.watchdogRecoveries++
                    StreamStats.watchdogLastReason = "session went silent"
                    StreamStats.watchdogLastMs = now
                    Logger.w("Watchdog: no audio for ${(now - StreamStats.audioLastArrivalMs)}ms and no " +
                        "video for ${(now - StreamStats.videoLastArrivalMs)}ms, with the socket still " +
                        "open — ending the session so the sender re-establishes it")
                    onStreamDead()
                }
                continue
            }

            // Rule 1b: there is no Surface to draw to, so give the hardware decoder back.
            //
            // A MediaCodec is not an in-process object — it is one of a handful of AVC decoder
            // instances the whole device has, and on a stick that handful is very small. Holding one
            // while Ferry has nothing to draw on means the next app to want a decoder (or Ferry's own
            // next session) contends for, or fails to get, hardware that is doing nothing.
            //
            // [decodeFrame] already rebuilds against a null Surface, so this only covers the case it
            // cannot reach: the Surface has gone AND no frames are arriving to notice it with. That
            // is the ordinary shape of backgrounding Ferry mid-cast on a paused sender, which iOS
            // will happily leave in place for as long as the screen is static.
            //
            // Sitting AHEAD of rule 2 matters just as much as what it does. With no Surface, "frames
            // are arriving and none is reaching the screen" is not a fault, it is the definition of
            // the situation — so rule 2 used to match, every tick, for as long as the app stayed
            // backgrounded with a live cast (which is what `receiveWhenClosed` is for). That is a
            // decoder rebuild every few seconds, forever, each one counted on the HUD as a watchdog
            // recovery from a stall that was never happening.
            if (surfaceProvider() == null && decoder != null) {
                Logger.i("Watchdog: no Surface to render to — releasing the hardware decoder until one returns")
                releaseIdleDecoder = true
                continue
            }

            // The displayed frame rate, sampled on the same one-second tick as everything else.
            // See StreamStats.videoShownFps for why this is the number that was missing.
            val shownNow = StreamStats.videoShown
            StreamStats.videoShownFps = (shownNow - lastFramesShown).coerceAtLeast(0)
            // A frame reaching the screen is the only proof a rebuild worked, so it is the only
            // thing that clears the escalation counter. Clearing it on anything else — a rebuild
            // completing, frames arriving — is what would let the loop below run forever again.
            if (shownNow > lastFramesShown) consecutiveStallRebuilds = 0
            lastFramesShown = shownNow

            // Rule 1c: the pipeline is losing frames steadily. Escalate — rebuild, then recycle.
            if (checkSustainedLoss(now)) continue

            // Rule 2: frames are arriving but none is reaching the screen — the decoder is wedged.
            if (!Companion.isStalled(now, firstArrivalMs, StreamStats.videoLastArrivalMs, StreamStats.videoLastShownMs)) {
                continue
            }
            // Describe the state that was found, not the action taken — the point of putting this on
            // screen is to learn what went wrong even when the recovery works and nobody sees a
            // freeze. "decoder missing" and "decoder stuck" are different bugs.
            val reason = if (decoder == null) "decoder missing" else "decoder stuck"
            StreamStats.watchdogRecoveries++
            StreamStats.watchdogLastReason = reason
            StreamStats.watchdogLastMs = now

            // A rebuild that changed nothing means rebuilding is the wrong remedy — escalate.
            //
            // **Reported from hardware, and the log is unambiguous: 179 rebuilds, one a second, for
            // seven minutes.** The user paused an iPad video for several minutes and resumed it;
            // audio came back, the picture stayed frozen on the paused frame, and this rule sat there
            // rebuilding the decoder forever without ever escalating.
            //
            // It could not have worked, and the reason is worth stating exactly. A freshly built
            // H.264 decoder has no reference picture, so it can produce **nothing at all** until an
            // IDR arrives — and on a resume iOS may not send one for a very long time. Every frame
            // arriving in the meantime is a P-frame predicting from pictures this decoder never had.
            // So each rebuild produced a decoder in precisely the state that cannot recover, and the
            // next tick built another one. `no keyframe for 3000ms — resuming decode` fired over and
            // over in that log, which is this exact situation: feeding predicted frames to a codec
            // that has no reference to predict from.
            //
            // Ending the session is the only remedy that reaches the cause, because a fresh SETUP
            // makes the sender start a new stream — which begins with a keyframe. It is also exactly
            // what the user does by hand when they stop and restart the share, and they have already
            // confirmed that works.
            consecutiveStallRebuilds++
            if (Companion.shouldRecycleAfterStall(consecutiveStallRebuilds, now, lastRecycleMs)) {
                lastRecycleMs = now
                consecutiveStallRebuilds = 0
                StreamStats.watchdogLastReason = "$reason — session recycled"
                Logger.w("Watchdog: $consecutiveStallRebuilds decoder rebuilds changed nothing " +
                    "($reason) — a decoder with no keyframe cannot recover by being rebuilt. Ending " +
                    "the session so the sender starts a new stream, which begins with one.")
                onStreamDead()
                continue
            }
            Logger.w("Watchdog: no frame shown for ${(now - maxOf(StreamStats.videoLastShownMs, firstArrivalMs))}ms " +
                "while frames are still arriving ($reason) — forcing a decoder rebuild " +
                "(attempt $consecutiveStallRebuilds of $STALL_REBUILDS_BEFORE_RECYCLE)")
            forceRebuild = true
        }
    }

    /**
     * Watches the *rate* at which frames are being destroyed, and escalates when it stays bad.
     *
     * Loss here means frames Ferry received and then could not use — shed at the queue, or refused
     * by the decoder for want of an input buffer. Each one costs picture until the sender's next
     * IDR, so a sustained rate of them is never acceptable and never normal.
     *
     * It deliberately does **not** count [StreamStats.videoRenderSkips]. A render skip is the
     * pacing rule working as designed — the frame was decoded, it just was not displayed, and the
     * picture is perfect. Confusing "deliberately not shown" with "destroyed" would make a healthy
     * high-frame-rate stream look like a catastrophe and recycle a session that was fine, which is
     * the exact class of confident-but-wrong fix this file has collected before.
     *
     * @return true if this tick was handled here and the remaining rules should be skipped.
     */
    private fun checkSustainedLoss(nowMs: Long): Boolean {
        val inNow = framesIn
        val lostNow = framesDropped + decoderDrops
        val arrived = inNow - lastFramesIn
        val lost = lostNow - lastFramesLost
        lastFramesIn = inNow
        lastFramesLost = lostNow

        if (!Companion.isLosingFrames(arrived, lost)) {
            if (degradedTicks > 0) {
                Logger.i("Frame loss back to normal after ${degradedTicks}s")
                degradedTicks = 0
            }
            return false
        }
        degradedTicks++
        val pct = if (arrived > 0) lost * 100 / arrived else 0

        // Step 1, cheap: rebuild the decoder. Most of what lands here is a codec that has stopped
        // handing back input buffers, and a fresh one costs about a second of picture.
        if (degradedTicks == DEGRADED_REBUILD_TICKS) {
            Logger.w("Losing $pct% of frames for ${degradedTicks}s — rebuilding the decoder")
            StreamStats.watchdogRecoveries++
            StreamStats.watchdogLastReason = "frame loss $pct%"
            StreamStats.watchdogLastMs = nowMs
            forceRebuild = true
            return true
        }

        // Step 2, drastic: recycle the whole session, which is what stopping and restarting the
        // share does by hand. Only after the rebuild has been tried and has not helped.
        if (degradedTicks >= DEGRADED_RECYCLE_TICKS &&
            nowMs - lastRecycleMs >= RECYCLE_MIN_INTERVAL_MS
        ) {
            lastRecycleMs = nowMs
            degradedTicks = 0
            StreamStats.watchdogRecoveries++
            StreamStats.watchdogLastReason = "session recycled ($pct% loss)"
            StreamStats.watchdogLastMs = nowMs
            Logger.w("Still losing $pct% of frames after a decoder rebuild — ending the session so " +
                "the sender re-establishes it (the automatic equivalent of stopping and restarting " +
                "the share)")
            onStreamDead()
            return true
        }
        return true
    }

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
                // Honour a watchdog request before touching the queue. Clearing the backoff too is
                // the point: the rebuild-rate limit exists to stop a failing decoder thrashing, but
                // the watchdog only fires after seconds of a frozen picture, which is precisely the
                // case where waiting longer is the wrong answer.
                if (forceRebuild) {
                    forceRebuild = false
                    nextRebuildAllowedNs = 0L
                    discardDecoder()
                    nextRebuildAllowedNs = 0L
                }
                // Hand the hardware decoder back while there is no Surface for it (see [runWatchdog]
                // rule 1b). The backoff [discardDecoder] arms is cleared straight afterwards for the
                // same reason it is under forceRebuild: it exists to stop a *failing* decoder
                // thrashing, and this is not a failure — making the return from background wait it
                // out would just be a second of black picture for nothing.
                if (releaseIdleDecoder) {
                    releaseIdleDecoder = false
                    if (decoder != null) {
                        discardDecoder()
                        nextRebuildAllowedNs = 0L
                    }
                }
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    when (item) {
                        is Config -> configureDecoder(item.sps, item.pps)
                        // `decodeNalUnit` copies the frame into the codec's own input buffer before
                        // it returns, so the moment decodeFrame is done — accepted, dropped, or
                        // thrown out of — nothing can still be reading this buffer and it goes back
                        // to the pool. The `finally` covers the throw, which is the path that would
                        // otherwise leak a buffer on every decoder failure.
                        is Frame -> try { decodeFrame(item) } finally { recycleFrameBuffer(item.annexB) }
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
        publishDecoderState()
    }

    /**
     * Mirrors the decoder's existence into the HUD.
     *
     * The single most useful fact the overlay was missing. Diagnosing the 6.0.0 freeze came down to
     * working out whether a decoder existed at all, and that had to be inferred from which *other*
     * counters had stopped moving — twice, wrongly. Stating it outright turns that into a glance.
     */
    private fun publishDecoderState() {
        StreamStats.decoderState = when {
            decoder != null -> "ok"
            decoderRebuilds > 0 -> "rebuild x$decoderRebuilds"
            else -> "none"
        }
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
        decoderRebuilds++
        decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        StreamStats.videoAdvertised = "${width}x${height}"
        publishDecoderState()
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(frame: Frame) {
        val annexB = frame.annexB
        val length = frame.length
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            // "changed" only when there really was a surface to change from. [discardDecoder] nulls
            // [configuredSurface] to force a rebuild, so after every watchdog recovery this branch is
            // reached with the Surface perfectly intact — and it used to announce "surface changed",
            // which sends anyone reading the log to investigate a Surface lifecycle that was never
            // involved. In a captured log that false line appeared 179 times.
            Logger.i("Mirror: " + when {
                liveSurface == null -> "surface lost"
                configuredSurface == null -> "no decoder attached"
                else -> "surface changed"
            } + " — re-attaching decoder")
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

        /** Reader, decoder, watchdog — one thread each, and no more. See [threads]. */
        private const val WORKER_THREADS = 3

        /**
         * Decrypted-frame buffers kept for reuse. See [bufferPool].
         *
         * Sized against the queue depth Ferry actually runs at, not against [QUEUE_CAPACITY]. A full
         * episode of real mirroring reported a depth of 1 out of 16 essentially throughout, so eight
         * covers the in-flight frames with room for a burst; the queue is allowed to reach 16, but
         * only transiently, and the few extra buffers a burst needs are worth allocating rather than
         * holding 16 large arrays permanently on a device this size. Overshoot costs memory, and
         * undershoot costs an allocation — the same one that used to happen every single frame.
         */
        private const val FRAME_POOL_CAPACITY = 8

        /**
         * Largest frame the pool will hold a buffer for, and therefore the ceiling on what it costs:
         * [FRAME_POOL_CAPACITY] × this, so 4 MB worst case.
         *
         * Comfortably above a real 1080p mirroring frame — predicted frames run tens of kilobytes
         * and even a keyframe is typically 100–300 KB — so in practice nothing exceeds it and every
         * frame is pooled. Anything that does is a one-off array, which costs a single allocation
         * rather than permanently re-cutting all eight buffers to its size.
         */
        private const val POOLED_FRAME_LIMIT = 512 * 1024

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

        /**
         * How long the picture may sit still, while frames are still arriving, before the watchdog
         * forces a rebuild.
         *
         * Must stay comfortably above [RESYNC_GIVE_UP_MS]. A keyframe resync legitimately shows
         * nothing for up to that long — frames arrive and are deliberately skipped while waiting for
         * an IDR — so a shorter deadline here would tear down a decoder that was recovering exactly
         * as designed, on a schedule guaranteed to keep doing it.
         */
        private const val STALL_RECOVER_MS = RESYNC_GIVE_UP_MS + 2_000L

        /**
         * How recently a frame must have arrived for a stall to count as one.
         *
         * This is the condition that stops the watchdog firing on a **static screen**, and without it
         * the whole mechanism would be actively harmful. iOS sends frames only when something
         * changes, so a paused video or a still menu legitimately produces no frames and no
         * rendering for minutes at a time. A watchdog reading only "nothing has been shown lately"
         * would decide that healthy idling was a fault and rebuild the decoder on a timer, forever.
         *
         * Pairing the two conditions makes the signal specific: frames *are* coming in, and none of
         * them is reaching the screen. That is never normal.
         */
        private const val STALL_ARRIVAL_FRESH_MS = 1_500L

        /** Watchdog tick. Slow — this is a stall detector, not a scheduler. */
        private const val WATCHDOG_INTERVAL_MS = 1_000L

        /**
         * Frames that must arrive in one tick before loss is judged at all.
         *
         * Below this the sample is too small to mean anything, and iOS legitimately sends almost
         * nothing when the screen is static — where one unlucky dropped frame out of three would
         * read as 33% loss and escalate against a session that is perfectly healthy and simply idle.
         */
        private const val MIN_ARRIVALS_TO_JUDGE = 10

        /** Fraction of arriving frames destroyed, in one tick, for that tick to count as degraded. */
        private const val DEGRADED_LOSS_PCT = 20

        /** Consecutive degraded seconds before the cheap remedy: rebuild the decoder. */
        private const val DEGRADED_REBUILD_TICKS = 5

        /** Consecutive degraded seconds before the drastic one: recycle the whole session. */
        private const val DEGRADED_RECYCLE_TICKS = 15

        /**
         * Floor on how often a session may be recycled.
         *
         * A recycle costs a visible reconnection, so on a link that is genuinely too poor to carry
         * the stream this has to settle into "degraded picture" rather than a reconnect loop — which
         * would be strictly worse than the problem it is treating. One minute is long enough that a
         * user sees at most a brief interruption, and short enough to still rescue a session that
         * went bad early in a long cast.
         */
        private const val RECYCLE_MIN_INTERVAL_MS = 60_000L

        /**
         * Whether one watchdog tick's worth of traffic counts as degraded.
         *
         * Pure, `internal` and in the companion so the escalation policy is unit-testable without a
         * socket or a codec — the same treatment [isStalled] and [waitBudgetMs] get, and for the
         * same reason: its failure mode (tearing down a healthy session) is destructive and cannot
         * be staged on a TV.
         *
         * @param arrived frames received in this tick.
         * @param lost of those, how many were shed at the queue or refused by the decoder.
         */
        internal fun isLosingFrames(arrived: Int, lost: Int): Boolean {
            if (arrived < MIN_ARRIVALS_TO_JUDGE) return false
            if (lost <= 0) return false
            return lost * 100 >= arrived * DEGRADED_LOSS_PCT
        }

        /**
         * Grace period after the data connection drops before the session is declared dead.
         *
         * Short, because there is nothing to wait for — no second accept() is coming (see
         * [runReader]) — but not zero, so an orderly teardown that closes the data socket a moment
         * before the control connection does not race into an unnecessary "session died" report.
         */
        private const val LINK_DEAD_GRACE_MS = 2_000L

        /**
         * Whether the video half of this session is dead beyond recovery, so the session should be
         * ended and the sender made to re-establish it.
         *
         * The trigger is **the data socket having actually closed**, and nothing else. That
         * narrowness is the whole design.
         *
         * The tempting alternative — "no frames have arrived for N seconds" — is unusable here, and
         * would be a worse bug than the one being fixed. iOS sends frames only when the screen
         * changes, so a paused video or a still menu produces no frames indefinitely while the
         * session is perfectly healthy. A timeout on arrivals would tear down a working cast every
         * time the user paused, which is exactly the sort of confident, wrong fix this file has
         * collected before. A closed socket is not ambiguous: the stream is gone.
         *
         * @param everConnected false before the sender has ever connected, when "disconnected" is
         *   merely the starting state and means nothing.
         */
        internal fun isStreamDead(
            nowMs: Long,
            everConnected: Boolean,
            dataConnected: Boolean,
            dataClosedAtMs: Long,
        ): Boolean {
            if (!everConnected || dataConnected) return false
            if (dataClosedAtMs <= 0L) return false
            return nowMs - dataClosedAtMs >= LINK_DEAD_GRACE_MS
        }

        /**
         * How long both streams must be silent before the session counts as dead.
         *
         * Generous, because the cost of being wrong is a visible reconnection. Realtime audio arrives
         * about ninety times a second, so eight seconds of nothing is roughly seven hundred missing
         * packets — not jitter, not a hiccup, and not a link that is coming back. The user in the
         * captured incident waited twelve seconds before giving up, so this recovers the session
         * before a person would have reached for the remote.
         */
        private const val SESSION_SILENT_MS = 8_000L

        /**
         * How many fruitless decoder rebuilds to accept before ending the session instead.
         *
         * Small, because a rebuild that did not produce a frame is *evidence the remedy is wrong*,
         * not an attempt that needs more patience — and the observed failure ran 179 of them. Large
         * enough that a rebuild which genuinely needed a moment (the codec settling, a keyframe a
         * second away) is not cut off before it can work.
         */
        private const val STALL_REBUILDS_BEFORE_RECYCLE = 5

        /**
         * Whether repeated failed rebuilds should escalate to ending the session.
         *
         * Shares [RECYCLE_MIN_INTERVAL_MS] with the frame-loss path deliberately: both end in the
         * same visible reconnection, so a link bad enough to trigger both must still not produce one
         * reconnect after another. Pure and `internal` so the escalation policy is testable without a
         * TV — it tears down live sessions, which is the same bar every other rule here is held to.
         */
        internal fun shouldRecycleAfterStall(
            consecutiveRebuilds: Int,
            nowMs: Long,
            lastRecycleMs: Long,
        ): Boolean {
            if (consecutiveRebuilds < STALL_REBUILDS_BEFORE_RECYCLE) return false
            return nowMs - lastRecycleMs >= RECYCLE_MIN_INTERVAL_MS
        }

        /**
         * Whether **both** halves of the session have gone quiet, with the socket still open.
         *
         * Requires audio to have arrived at least once ([audioLastArrivalMs] non-zero). That is the
         * guard that keeps this honest: with no audio stream there is no heartbeat, video silence
         * alone means nothing, and this must return false rather than guess. It is exactly the
         * ambiguity that made a timeout unusable in 6.7.0 — the difference now is that audio supplies
         * the missing half, not that the reasoning about video changed.
         *
         * Pure, `internal` and in the companion for the same reason as [isStalled] and
         * [isStreamDead]: it tears down live sessions, so it must be testable without a TV.
         */
        internal fun isSessionSilent(
            nowMs: Long,
            audioLastArrivalMs: Long,
            videoLastArrivalMs: Long,
        ): Boolean {
            if (audioLastArrivalMs <= 0L) return false          // no audio stream — no heartbeat
            if (videoLastArrivalMs <= 0L) return false          // nothing ever arrived; not our case
            return nowMs - audioLastArrivalMs >= SESSION_SILENT_MS &&
                nowMs - videoLastArrivalMs >= SESSION_SILENT_MS
        }

        /**
         * Whether the video path looks wedged: frames arriving, nothing reaching the screen.
         *
         * A pure function of three timestamps so the policy can be unit-tested — the failure it
         * guards against is rare, timing-dependent, and impossible to stage on a TV, which makes it
         * exactly the kind of logic that must not be verified by reading it.
         *
         * @param firstArrivalMs when the first frame of this stream arrived, 0 if none yet. Needed
         *   for the case where *nothing* has ever been shown: measuring from the last arrival cannot
         *   detect it, because the last arrival is by definition fresh whenever this is reached.
         * @param lastArrivalMs when a frame last arrived, 0 if never.
         * @param lastShownMs when a frame last reached the screen, 0 if never.
         */
        internal fun isStalled(
            nowMs: Long,
            firstArrivalMs: Long,
            lastArrivalMs: Long,
            lastShownMs: Long,
        ): Boolean {
            if (lastArrivalMs <= 0L) return false                            // nothing has ever arrived
            if (nowMs - lastArrivalMs > STALL_ARRIVAL_FRESH_MS) return false  // static screen, not a stall
            // Measure from the last frame shown; if none ever was, from when frames started coming,
            // so a session that never produced a picture at all is caught rather than waited on.
            val reference = if (lastShownMs > 0L) lastShownMs else firstArrivalMs
            if (reference <= 0L) return false
            return nowMs - reference > STALL_RECOVER_MS
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
