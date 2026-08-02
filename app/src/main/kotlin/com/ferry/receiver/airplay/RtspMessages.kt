package com.ferry.receiver.airplay

/**
 * RtspRequest — Represents a parsed RTSP request received from the AirPlay sender.
 *
 * WHY: Parsing the raw TCP stream into a structured object lets each RTSP method handler
 * work with clean typed data instead of string manipulation.
 *
 * [bodyBytes] is the wire body and the authoritative form; [body] is a UTF-8 view of it, decoded
 * on first access.
 *
 * The decode is lazy because most bodies are not text. A photo PUT carries up to
 * [PhotoHandler.MAX_PHOTO_BYTES] (25 MB) of JPEG, and the binary-plist handshake bodies are not
 * text either. Decoding those eagerly built a multi-megabyte String of U+FFFD replacement
 * characters that nothing ever read — pure transient garbage on a heap-constrained TV, and a lever
 * a peer could pull deliberately. Text handlers (SDP, headers) still get [body] transparently.
 *
 * @param method    The RTSP method (e.g., "OPTIONS", "ANNOUNCE", "RECORD")
 * @param uri       The request URI (e.g., "rtsp://192.168.1.1/ferry")
 * @param headers   All headers as a key→value map (keys are case-sensitive per RFC 2326)
 * @param bodyBytes The raw request body (empty if Content-Length was 0 or absent)
 */
class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray = ByteArray(0),
    val protocol: String = "RTSP/1.0"
) {
    /** UTF-8 view of [bodyBytes], decoded on first access and cached. */
    val body: String by lazy(LazyThreadSafetyMode.NONE) { String(bodyBytes, Charsets.UTF_8) }

    /** Convenience for tests and callers that build a request from text. */
    constructor(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: String,
        protocol: String = "RTSP/1.0"
    ) : this(method, uri, headers, body.toByteArray(Charsets.UTF_8), protocol)

    override fun toString(): String =
        "RtspRequest($method $uri $protocol, ${headers.size} headers, ${bodyBytes.size}B body)"
}

/**
 * RtspResponse — Represents an RTSP response to send back to the AirPlay sender.
 *
 * WHY: Building responses as data objects (rather than raw strings) makes the handler
 * methods easier to test — they return typed data, not side-effectful writes.
 *
 * @param statusCode    HTTP-like status code (200 = OK, 400 = Bad Request, 503 = Unavailable, …)
 * @param statusMessage Human-readable status phrase (e.g., "OK", "Not Found")
 * @param headers       Optional extra headers included in the serialized response
 * @param body          Optional text response body (e.g., SDP for a re-ANNOUNCE)
 * @param bodyBytes     Optional binary response body. When non-null this is the exact
 *                      wire body (used for AirPlay 2 binary plists, FairPlay, encrypted
 *                      payloads). Takes precedence over [body].
 * @param contentType   Optional Content-Type header (e.g. "application/x-apple-binary-plist").
 */
data class RtspResponse(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val bodyBytes: ByteArray? = null,
    val contentType: String? = null,
    val protocol: String = "RTSP/1.0"
)

/** Effective wire body: prefers binary [RtspResponse.bodyBytes], else UTF-8 of [RtspResponse.body]. */
fun RtspResponse.wireBody(): ByteArray = bodyBytes ?: body.toByteArray(Charsets.UTF_8)
