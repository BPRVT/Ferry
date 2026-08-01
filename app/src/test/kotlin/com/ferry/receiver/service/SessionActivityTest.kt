package com.ferry.receiver.service

import com.ferry.receiver.airplay.NowPlayingInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionActivityTest — covers [isSessionActive], which decides both which overlay shows and
 * whether the display is held awake (FLAG_KEEP_SCREEN_ON).
 *
 * WHY THIS MATTERS: the two failure modes are asymmetric.
 *  - False negative → the Fire TV screensaver interrupts an active mirror (the original bug).
 *  - False positive → the TV never sleeps again, which is worse. The idle case below is the
 *    acceptance criterion, so it is tested from every direction.
 *
 * These call the real production function rather than restating its logic, so the test cannot
 * silently drift away from the behavior it is meant to pin down.
 */
class SessionActivityTest {

    private val nowPlaying = NowPlayingInfo(senderName = "Test Mac", title = "Track")
    private val photo = PhotoFrame(bytes = byteArrayOf(1, 2, 3), mimeType = "image/jpeg")

    private fun active(
        state: ProtocolState = ProtocolState.ADVERTISING,
        np: NowPlayingInfo? = null,
        pf: PhotoFrame? = null,
        pin: String? = null
    ) = isSessionActive(state, np, pf, pin)

    // ─── Idle: the screensaver MUST be allowed to run ────────────────────────

    @Test
    fun `idle while advertising is not active`() {
        assertFalse(active(state = ProtocolState.ADVERTISING))
    }

    @Test
    fun `every non-connected protocol state with no media is inactive`() {
        // The acceptance criterion: nothing connected means normal sleep behavior resumes.
        for (state in ProtocolState.values().filter { it != ProtocolState.CONNECTED }) {
            assertFalse("$state with no media should be inactive", active(state = state))
        }
    }

    // ─── Active: each thing that must hold the screen on ─────────────────────

    @Test
    fun `connected mirroring session is active`() {
        assertTrue(active(state = ProtocolState.CONNECTED))
    }

    @Test
    fun `audio-only session is active even when not CONNECTED`() {
        // Buffered audio shows the now-playing card; the protocol state can lag behind it.
        assertTrue(active(state = ProtocolState.ADVERTISING, np = nowPlaying))
    }

    @Test
    fun `displayed photo is active`() {
        assertTrue(active(state = ProtocolState.ADVERTISING, pf = photo))
    }

    @Test
    fun `pairing PIN is active`() {
        // A screensaver over the PIN would make pairing impossible.
        assertTrue(active(state = ProtocolState.ADVERTISING, pin = "1234"))
    }

    // ─── Transitions — the cases implementations get wrong ───────────────────

    @Test
    fun `session ending returns to inactive`() {
        assertTrue(active(state = ProtocolState.CONNECTED))
        // TEARDOWN, or an abrupt drop: both land on ADVERTISING via onStreamingStopped().
        assertFalse(active(state = ProtocolState.ADVERTISING))
    }

    @Test
    fun `abrupt drop clears media state and is inactive`() {
        // Sender walks out of Wi-Fi range: the RTSP finally-block clears everything at once.
        assertFalse(active(state = ProtocolState.ADVERTISING, np = null, pf = null, pin = null))
    }

    @Test
    fun `receiver stopped or errored is inactive`() {
        assertFalse(active(state = ProtocolState.DISABLED))
        assertFalse(active(state = ProtocolState.ERROR))
    }

    @Test
    fun `photo cleared while still connected stays active`() {
        // Photo → mirroring handoff must not blink the flag off.
        assertTrue(active(state = ProtocolState.CONNECTED, pf = null))
    }

    @Test
    fun `any single active signal is sufficient`() {
        assertTrue(active(np = nowPlaying))
        assertTrue(active(pf = photo))
        assertTrue(active(pin = "0000"))
        assertTrue(active(state = ProtocolState.CONNECTED))
    }
}
