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
     * @param disposable true when no slice in this frame is used as a reference by any later
     *   frame (every slice NAL has `nal_ref_idc == 0`), so dropping it under load costs a single
     *   invisible frame and needs no keyframe resync. Computed once on the reader thread.
     */
    private class Frame(val annexB: ByteArray, val disposable: Boolean) : Item()

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0)            // OS-assigned free port
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
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
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false

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
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> {
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) enqueue(Frame(annexB, Companion.isDisposable(annexB)))
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
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
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) keyframeWaits=$keyframeWaits " +
                "queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    private fun parseConfig(payload: ByteArray): Config? = try {
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
        val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
            (payload[ppsLenOffset + 1].toInt() and 0xFF)
        Config(sps, payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize))
    } catch (e: Exception) {
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Config -> configureDecoder(item.sps, item.pps)
                    is Frame -> decodeFrame(item.annexB)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
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

    private fun decodeFrame(annexB: ByteArray) {
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        }
        val d = decoder ?: return                              // need surface + SPS/PPS first
        if (!d.isHealthy) {                                    // error state — drop, await next config
            Logger.w("Mirror: decoder unhealthy — dropping, awaiting new SPS/PPS")
            d.release(); decoder = null; configuredSurface = null; lastSps = null; lastPps = null
            return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        d.decodeNalUnit(annexB, framePtsUs)
        framePtsUs += FRAME_INTERVAL_US
    }

    /** True if the Annex-B frame contains an IDR NAL unit (type 5) — a decodable resync point. */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if ((annexB[i + 3].toInt() and 0x1F) == 5) return true   // IDR slice
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
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

        private const val READ_BUFFER = 256 * 1024             // > one frame, so most reads miss the OS
        private const val SOCKET_RCVBUF = 1024 * 1024          // kernel-side burst absorption
        private const val NAL_SLICE_NON_IDR = 1
        private const val NAL_SLICE_IDR = 5
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L

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
        internal fun isDisposable(annexB: ByteArray): Boolean {
            var sawSlice = false
            var i = 0
            while (i + 3 < annexB.size) {
                if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                    val header = annexB[i + 3].toInt()
                    when (header and 0x1F) {
                        NAL_SLICE_NON_IDR, NAL_SLICE_IDR -> {
                            sawSlice = true
                            // nal_ref_idc != 0 → a later frame may predict from this slice.
                            if ((header and 0x60) != 0) return false
                        }
                    }
                    i += 4
                } else {
                    i++
                }
            }
            return sawSlice
        }
    }
}
