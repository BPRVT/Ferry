package com.ferry.receiver.airplay

import com.ferry.receiver.util.Logger
import java.io.InputStream

/**
 * RtspRequestReader parses RTSP/HTTP-style requests from the AirPlay control socket.
 *
 * The reader consumes bytes directly from the socket so the caller can safely switch
 * to binary RTP interleaved frames after RTSP RECORD without losing buffered data.
 */
internal class RtspRequestReader(
    private val maxMessageBytes: Int,
    private val maxPhotoBytes: Int
) {
    /**
     * Reads one complete RTSP or AirPlay photo request from [inputStream].
     *
     * Returns null on clean EOF, malformed input, or payloads over the configured limits.
     */
    fun read(inputStream: InputStream): RtspRequest? {
        // Skip leading blank lines (RFC 2326 tolerates them between messages), but iteratively and
        // with a hard cap.
        //
        // SECURITY: this used to recurse — `if (requestLine.isBlank()) return read(inputStream)`.
        // Kotlin does not tail-optimise that, so a peer sending a few thousand newlines produced a
        // stack frame each and killed the connection thread with StackOverflowError. A loop cannot
        // overflow, and the cap stops a peer holding the thread open on blank lines forever.
        var requestLine = readLine(inputStream) ?: return null
        var blanks = 0
        while (requestLine.isBlank()) {
            if (++blanks > MAX_LEADING_BLANK_LINES) {
                Logger.w("RTSP: too many blank lines before a request line — rejecting")
                return null
            }
            requestLine = readLine(inputStream) ?: return null
        }

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            Logger.w("Malformed RTSP request line: '$requestLine'")
            return null
        }

        val headers = readHeaders(inputStream, requestLine.length) ?: return null
        val method = parts[0]
        val uri = parts[1]
        val protocol = parts[2]
        val bodyBytes = readBody(inputStream, method, uri, headers) ?: return null

        return RtspRequest(
            method = method,
            uri = uri,
            headers = headers,
            bodyBytes = bodyBytes,
            protocol = protocol
        )
    }

    private fun readHeaders(inputStream: InputStream, requestLineBytes: Int): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        var totalBytes = requestLineBytes

        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isEmpty()) return headers

            totalBytes += line.length
            if (totalBytes > maxMessageBytes) {
                Logger.w("RTSP message too large — rejecting")
                return null
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim()] =
                    line.substring(colonIndex + 1).trim()
            }
        }
    }

    private fun readBody(
        inputStream: InputStream,
        method: String,
        uri: String,
        headers: Map<String, String>
    ): ByteArray? {
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val bodyLimit = if (method == "PUT" && uri.substringBefore("?") == PhotoHandler.PHOTO_PATH) {
            maxPhotoBytes
        } else {
            maxMessageBytes
        }

        if (contentLength > bodyLimit) {
            Logger.w("Request body too large ($contentLength bytes) — rejecting")
            return null
        }
        if (contentLength <= 0) return ByteArray(0)

        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = inputStream.read(buf, read, contentLength - read)
            if (n == -1) return null
            read += n
        }
        return buf
    }

    private fun readLine(inputStream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inputStream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > maxMessageBytes) return null
        }
    }

    private companion object {
        /** Generous for any real sender; bounded so blank lines cannot occupy a thread indefinitely. */
        const val MAX_LEADING_BLANK_LINES = 64
    }
}
