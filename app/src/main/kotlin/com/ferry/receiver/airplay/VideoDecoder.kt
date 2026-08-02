package com.ferry.receiver.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.ferry.receiver.util.Logger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * VideoDecoder — Hardware H.264 video decoder using Android's MediaCodec API.
 *
 * WHY: AirPlay screen mirroring sends video as a stream of H.264-encoded frames.
 * To display these frames on screen, we need to decode the H.264 bitstream.
 * MediaCodec is Android's API to the hardware video decoder (GPU), which is
 * much faster and more power-efficient than any software decoder.
 *
 * HOW: Initialize with a [Surface] (from StreamingScreen) and the codec parameters
 * from the SDP (received in the RTSP ANNOUNCE). Then call [decodeNalUnit] for each
 * video chunk received from the RTP stream. MediaCodec outputs decoded frames directly
 * to the Surface — no intermediate buffer copies.
 *
 * AirPlay video data flow:
 *   RTP packet → RtspHandler strips RTP header → NAL unit bytes → VideoDecoder.decodeNalUnit()
 *   → MediaCodec input buffer → GPU hardware decode → Surface (displayed on TV)
 *
 * Example:
 *   val decoder = VideoDecoder(surface)
 *   decoder.initialize(spsBytes, ppsBytes, width, height)  // call once with SDP params
 *   decoder.decodeNalUnit(nalUnitBytes)                    // call for each video chunk
 *   decoder.release()                                       // call when done
 */
class VideoDecoder(private val outputSurface: Surface) {

    // The underlying hardware decoder — null until initialize() is called
    private var mediaCodec: MediaCodec? = null

    // Track whether the decoder has been initialized (to prevent double-init)
    @Volatile
    private var isInitialized = false

    /**
     * False once MediaCodec has thrown (entered an unrecoverable error state). The caller should
     * drop this decoder and create a fresh one — error state cannot be cleared by reconfigure.
     */
    @Volatile
    var isHealthy = true
        private set

    /**
     * Input buffer indices MediaCodec has handed us via [MediaCodec.Callback.onInputBufferAvailable]
     * but that we have not filled yet.
     *
     * In async mode the codec pushes indices here as they free up, so [decodeNalUnit] takes one that
     * is *already* available instead of asking the codec and blocking. Capacity is well above any
     * real codec's input buffer count; overflow would just mean discarding a usable index.
     */
    private val availableInputBuffers = ArrayBlockingQueue<Int>(64)

    /**
     * MediaCodec delivers async callbacks on this thread's Looper. It must not be the main thread
     * (a stalled UI would stall decode) nor the decoder thread (which blocks in [decodeNalUnit]).
     */
    private var callbackThread: HandlerThread? = null

    /**
     * Async-mode callbacks. The important one is [onOutputBufferAvailable]: it renders each frame
     * the moment the codec finishes it, on the codec's own thread.
     *
     * The previous synchronous design drained output only from inside [decodeNalUnit], so a decoded
     * frame sat undrained until the *next* frame arrived to push it out — a frame of latency built
     * into the structure, plus a stall of up to the old input-buffer timeout on every frame whose
     * buffer was not ready. Neither exists here.
     */
    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Offer, not put: if we somehow have more free indices than the queue holds, dropping
            // one is harmless (the codec will hand it back again) and blocking the codec is not.
            availableInputBuffers.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            // Render immediately. We deliberately do NOT schedule a future render time for A/V sync:
            // this Surface's BufferQueue holds only ~3 frames, so any hold quickly back-pressures the
            // decoder → the upstream frame queue saturates → big latency + dropped (corrupt) frames.
            // A/V alignment is handled by keeping the AUDIO path low-latency instead (AudioStreamServer).
            try {
                codec.releaseOutputBuffer(index, true)
            } catch (e: IllegalStateException) {
                // Raced with release() — the codec is gone. Nothing to render to.
                Logger.v { "VideoDecoder: output buffer released after shutdown (${e.message})" }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // The decoder parsed the real size from the SPS — authoritative for aspect-fit.
            publishOutputSize(format)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            // Error state cannot be cleared by reconfigure; MirrorStreamServer watches isHealthy
            // and builds a fresh decoder.
            Logger.e("VideoDecoder entered error state — will recreate", e)
            isHealthy = false
        }
    }

    /**
     * Initializes the MediaCodec decoder with the video stream parameters from the SDP.
     *
     * This must be called ONCE before any calls to [decodeNalUnit].
     * The parameters (SPS, PPS, width, height) come from the SDP body of the
     * RTSP ANNOUNCE message.
     *
     * What is SPS/PPS?
     *   H.264 requires two special "configuration" NAL units before the first frame:
     *   - SPS (Sequence Parameter Set): describes the video resolution, profile, level
     *   - PPS (Picture Parameter Set): describes encoding parameters for each frame
     *   MediaCodec needs these to configure the hardware decoder correctly.
     *
     * RULE 5: If initialization fails, the exception propagates to the caller
     * (RtspHandler) which will handle it gracefully (log + return to WAITING state).
     *
     * @param spsBytes  The SPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param ppsBytes  The PPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param width     Video width in pixels (from SDP)
     * @param height    Video height in pixels (from SDP)
     */
    fun initialize(spsBytes: ByteArray, ppsBytes: ByteArray, width: Int, height: Int) {
        if (isInitialized) {
            Logger.w("VideoDecoder.initialize() called twice — ignoring second call")
            return
        }

        // Try to extract actual resolution from the SPS NAL unit. The parser can misread some senders'
        // SPS (e.g. iPhone) and return nonsense like 32x87392 — configuring MediaCodec with that wedges
        // the decoder (no input buffers, no frames). Validate the result and fall back to the hint; the
        // hardware decoder reads the true size from the SPS (csd-0) itself and reports it via
        // INFO_OUTPUT_FORMAT_CHANGED, which we use to refine the size for aspect-fit.
        val parsed = Companion.parseSpsResolution(spsBytes)
        val (actualWidth, actualHeight) = parsed?.takeIf { isPlausibleSize(it.first, it.second) } ?: run {
            Logger.w("SPS resolution $parsed implausible/failed — using hint ${width}x${height}")
            Pair(width, height)
        }

        Logger.i("Initializing H.264 decoder: ${actualWidth}x${actualHeight} " +
                 "(hint was ${width}x${height})")

        // Provisional size for StreamingScreen aspect-fit; replaced by the decoder's reported size.
        StreamStats.videoWidth = actualWidth
        StreamStats.videoHeight = actualHeight

        // Create the MediaFormat that describes the H.264 stream to the hardware decoder
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,  // AVC = Advanced Video Coding = H.264
            actualWidth,
            actualHeight
        ).apply {
            // Provide SPS and PPS so MediaCodec can configure the hardware decoder.
            // These are wrapped in ByteBuffers as required by the MediaCodec API.
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsBytes))  // SPS
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsBytes))  // PPS
            // NOTE: do NOT set KEY_MAX_WIDTH/HEIGHT here — this SoC's MStar decoder rejects
            // adaptive playback (BadParameter / buffer-count failures) and produces banding.
            // Resolution changes are handled by recreating the decoder in MirrorStreamServer.

            // Mirroring is a realtime workload: a frame decoded late is worth less than a frame
            // decoded now, and there is no seek/scrub to amortise buffering against. All three
            // hints below trade power for latency, which is the right trade here.
            //
            // KEY_PRIORITY 0 = realtime (default 1 = best-effort). API 23+.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // Tells the codec the rate we actually need, so its governor holds clocks up instead of
            // settling at a power-saving operating point and then chasing the stream. API 23+.
            setInteger(MediaFormat.KEY_OPERATING_RATE, TARGET_FPS)
            // Disables output reordering — the decoder emits each frame as soon as it is decoded
            // rather than holding frames to satisfy B-frame display order. Worth several frames of
            // latency on its own. API 30+; older Fire OS builds simply never take this branch.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        // Create the hardware H.264 decoder.
        // "video/avc" is the MIME type for H.264. Android will pick the best
        // available hardware decoder for this format.
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec = codec

        // Async mode. setCallback MUST precede configure(). See [callbackHandler] for why this runs
        // on its own thread rather than the caller's.
        val thread = HandlerThread("FerryVideoDecoder").also { it.start() }
        callbackThread = thread
        codec.setCallback(decoderCallback, Handler(thread.looper))

        // Configure the decoder:
        // - format: what the input will look like (H.264, width, height, SPS/PPS)
        // - outputSurface: where decoded frames go (directly to screen — no intermediate copy)
        // - crypto: null (we handle decryption before this point, if needed)
        // - flags: 0 (0 = decoder mode; CONFIGURE_FLAG_ENCODE would be for encoding)
        codec.configure(format, outputSurface, null, 0)
        codec.start()

        isInitialized = true
        Logger.i("H.264 decoder initialized (async, codec=${runCatching { codec.name }.getOrNull()}, " +
                 "lowLatency=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.R})")
    }

    /**
     * Decodes a single H.264 NAL unit and sends it to the display.
     *
     * A NAL unit (Network Abstraction Layer Unit) is the basic building block of H.264.
     * Each RTP packet from AirPlay contains one or more NAL units.
     * Some NAL units are full frames (IDR frames), others are partial updates.
     *
     * PERFORMANCE: This method runs on the IO coroutine dispatcher (network thread).
     * MediaCodec handles the actual decoding on its own internal thread.
     * The decoded frame appears on the Surface without any UI thread involvement.
     *
     * SECURITY: The caller (RtspHandler) is responsible for validating the byte array
     * length before passing it here.
     *
     * @param nalUnit The raw NAL unit bytes (without the RTP header).
     * @param presentationTimeUs Presentation timestamp in microseconds (for A/V sync).
     */
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long, length: Int = nalUnit.size) {
        val codec = mediaCodec ?: run {
            Logger.w("decodeNalUnit() called but decoder not initialized")
            return
        }

        try {
            // Take an input buffer the codec has ALREADY handed us. The brief poll rides out normal
            // codec jitter; unlike the old dequeueInputBuffer() wait it costs nothing in the common
            // case, because a free index is almost always sitting in the queue.
            val inputBufferIndex = availableInputBuffers.poll(INPUT_BUFFER_WAIT_MS, TimeUnit.MILLISECONDS)

            if (inputBufferIndex != null) {
                // We got an input buffer — fill it with the NAL unit bytes
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                inputBuffer.clear()
                // [length] may be shorter than the array: the mirror path converts AVCC to Annex-B
                // in place and reports how many bytes are valid, rather than allocating an
                // exactly-sized copy per frame.
                inputBuffer.put(nalUnit, 0, length)

                // Tell MediaCodec: "input buffer [index] is filled with [size] bytes
                // of data with timestamp [presentationTimeUs] — please decode it"
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,                 // offset: start from beginning of buffer
                    length,            // size: how many bytes to decode
                    presentationTimeUs,
                    0                  // flags: 0 = normal frame (not end-of-stream)
                )
            } else {
                // No input buffer available — the decoder is catching up.
                // Drop this NAL unit to avoid building up backlog (prefer low latency).
                Logger.v { "VideoDecoder: no input buffer available, dropping NAL unit" }
            }

        } catch (e: IllegalStateException) {
            // MediaCodec is now in the error state and cannot recover — flag for recreation.
            Logger.e("VideoDecoder entered error state — will recreate", e)
            isHealthy = false
        } catch (e: Exception) {
            Logger.e("Error decoding NAL unit", e)
        }
    }

    /** Reads the decoder's true display size (honouring the crop rectangle) for StreamingScreen. */
    private fun publishOutputSize(format: MediaFormat) {
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        if (isPlausibleSize(w, h)) {
            StreamStats.videoWidth = w
            StreamStats.videoHeight = h
            Logger.i("Video output size ${w}x$h")
        }
    }

    /**
     * Releases all MediaCodec resources.
     *
     * MUST be called when streaming ends (from AirPlayReceiver.onStreamingStopped()
     * or from onDestroy()). Failing to release MediaCodec causes:
     * - Memory leaks (codec buffers are GPU memory, a scarce resource)
     * - The hardware decoder being unavailable to other apps
     *
     * After release(), call initialize() again before using the decoder.
     */
    fun release() {
        Logger.d("Releasing VideoDecoder")
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing MediaCodec (non-fatal)", e)
        } finally {
            mediaCodec = null
            isInitialized = false
            // Stop the callback Looper AFTER the codec is gone, so no callback can arrive for a
            // released codec. quitSafely() lets any in-flight callback finish first.
            callbackThread?.quitSafely()
            callbackThread = null
            availableInputBuffers.clear()
        }
    }

    /** True if (w, h) look like a real video resolution — rejects parser garbage (e.g. 32x87392). */
    private fun isPlausibleSize(w: Int, h: Int): Boolean = w in 64..8192 && h in 64..8192

    companion object {
        /** Rate hint given to the codec's governor via KEY_OPERATING_RATE. */
        private const val TARGET_FPS = 60

        /**
         * How long [decodeNalUnit] waits for a free input buffer index before dropping the frame.
         *
         * Much shorter than the 12 ms this needed in synchronous mode. There, the wait *was* the
         * mechanism for getting a buffer, so it had to cover codec jitter. Here the codec pushes
         * indices into [availableInputBuffers] as they free up, so in the common case one is already
         * waiting and the poll returns instantly; this timeout only covers the genuine case of the
         * decoder being saturated, where dropping promptly beats stalling the decoder thread.
         */
        private const val INPUT_BUFFER_WAIT_MS = 4L

        /**
         * Parses the H.264 SPS NAL unit to extract the actual video resolution.
         *
         * WHY: AirPlay SDP does not include explicit width/height fields.
         * The true resolution is encoded inside the SPS (Sequence Parameter Set) NAL unit
         * as `pic_width_in_mbs_minus1` and `pic_height_in_map_units_minus1`.
         * Each macroblock (MB) is 16×16 pixels.
         *
         * Formula:
         *   width  = (pic_width_in_mbs_minus1  + 1) × 16
         *   height = (pic_height_in_map_units_minus1 + 1) × 16
         *
         * Handles Baseline (66), Main (77), and High profile (100+) SPS formats.
         * Returns null (graceful fallback) if the SPS is too short, malformed, or uses
         * an unsupported bitstream feature.
         *
         * Exposed as `internal` for unit testing without an Android runtime.
         *
         * @param sps The raw SPS NAL unit bytes (first byte is the NAL type, 0x67).
         * @return (width, height) in pixels, or null if parsing fails.
         */
        internal fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
            try {
                if (sps.size < 4) return null
                val reader = SpsBitReader(sps, startOffset = 1)  // skip NAL type byte (0x67)

                val profileIdc = reader.readBits(8)
                reader.readBits(8)   // constraint flags + 2 reserved zeros
                reader.readBits(8)   // level_idc
                reader.readUe()      // seq_parameter_set_id

                // Default for Baseline/Main profiles per H.264 spec.
                var chromaFormatIdc = 1
                var separateColorPlaneFlag = 0

                // High-profile and variants have extra fields before the common fields.
                val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
                if (profileIdc in highProfiles) {
                    chromaFormatIdc = reader.readUe()
                    if (chromaFormatIdc == 3) {
                        separateColorPlaneFlag = reader.readBits(1)  // separate_colour_plane_flag
                    }
                    reader.readUe()     // bit_depth_luma_minus8
                    reader.readUe()     // bit_depth_chroma_minus8
                    reader.readBits(1)  // qpprime_y_zero_transform_bypass_flag
                    if (reader.readBits(1) == 1) {  // seq_scaling_matrix_present_flag
                        val count = if (chromaFormatIdc != 3) 8 else 12
                        repeat(count) {
                            if (reader.readBits(1) == 1) {  // seq_scaling_list_present_flag[i]
                                reader.skipScalingList(if (it < 6) 16 else 64)
                            }
                        }
                    }
                }

                reader.readUe()   // log2_max_frame_num_minus4
                val picOrderCntType = reader.readUe()
                when (picOrderCntType) {
                    0 -> reader.readUe()  // log2_max_pic_order_cnt_lsb_minus4
                    1 -> {
                        reader.readBits(1)  // delta_pic_order_always_zero_flag
                        reader.readSe()     // offset_for_non_ref_pic
                        reader.readSe()     // offset_for_top_to_bottom_field
                        repeat(reader.readUe()) { reader.readSe() }
                    }
                }

                reader.readUe()     // max_num_ref_frames
                reader.readBits(1)  // gaps_in_frame_num_value_allowed_flag

                val picWidthInMbsMinus1 = reader.readUe()
                val picHeightInMapUnitsMinus1 = reader.readUe()
                val frameMbsOnlyFlag = reader.readBits(1)
                if (frameMbsOnlyFlag == 0) {
                    reader.readBits(1)  // mb_adaptive_frame_field_flag
                }
                reader.readBits(1)  // direct_8x8_inference_flag

                var cropLeft = 0
                var cropRight = 0
                var cropTop = 0
                var cropBottom = 0
                if (reader.readBits(1) == 1) {  // frame_cropping_flag
                    cropLeft = reader.readUe()
                    cropRight = reader.readUe()
                    cropTop = reader.readUe()
                    cropBottom = reader.readUe()
                }

                val codedWidth = (picWidthInMbsMinus1 + 1) * 16
                val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

                val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc

                val subWidthC = when (chromaArrayType) {
                    0 -> 1
                    1, 2 -> 2
                    else -> 1
                }
                val subHeightC = when (chromaArrayType) {
                    1 -> 2
                    else -> 1
                }

                val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
                val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

                val width = codedWidth - (cropLeft + cropRight) * cropUnitX
                val height = codedHeight - (cropTop + cropBottom) * cropUnitY
                if (width <= 0 || height <= 0) return null

                Logger.d("SPS parsed: ${width}x${height} (profile=$profileIdc)")
                return Pair(width, height)

            } catch (e: Exception) {
                Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
                return null
            }
        }

        /**
         * Bitstream reader for H.264 NAL units.
         *
         * Reads bits MSB-first and implements unsigned/signed Exp-Golomb coding.
         * Exposed as `internal` for use in tests.
         *
         * @param data        The raw SPS byte array (including NAL type header).
         * @param startOffset Byte offset to start reading from (typically 1 to skip NAL type).
         */
        class SpsBitReader(private val data: ByteArray, startOffset: Int) {
            private var bytePos = startOffset
            private var bitPos  = 7  // MSB first (bit 7 = most significant)

            fun readBit(): Int {
                if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
                val bit = (data[bytePos].toInt() ushr bitPos) and 1
                if (--bitPos < 0) { bitPos = 7; bytePos++ }
                return bit
            }

            fun readBits(n: Int): Int {
                var result = 0
                repeat(n) { result = (result shl 1) or readBit() }
                return result
            }

            /** Reads an unsigned Exp-Golomb code ue(v). */
            fun readUe(): Int {
                var leadingZeros = 0
                while (readBit() == 0) {
                    if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
                }
                return if (leadingZeros == 0) 0
                       else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
            }

            /** Reads a signed Exp-Golomb code se(v). */
            fun readSe(): Int {
                val k = readUe()
                return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
            }

            /** Skips a scaling list of [size] entries (High Profile SPS §7.3.2.1.1.1). */
            fun skipScalingList(size: Int) {
                var lastScale = 8
                var nextScale = 8
                repeat(size) {
                    if (nextScale != 0) {
                        val deltaScale = readSe()
                        nextScale = (lastScale + deltaScale + 256) % 256
                    }
                    lastScale = if (nextScale == 0) lastScale else nextScale
                }
            }
        }
    }
}
