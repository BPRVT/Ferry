package com.ferry.receiver.service

import com.ferry.receiver.airplay.NowPlayingInfo

/**
 * isSessionActive — the single definition of "something is currently being received".
 *
 * WHY: two things need this answer and they must never disagree:
 *   1. which full-screen overlay [com.ferry.receiver.MainActivity] shows, and
 *   2. whether the display is held awake (FLAG_KEEP_SCREEN_ON).
 *
 * If those were computed separately, any future state added to one would eventually
 * be forgotten in the other — and the failure mode is the TV either sleeping mid-stream
 * or never sleeping again. Both call this instead.
 *
 * HOW: mirrors the branches of MainActivity.updateOverlay() exactly. True whenever any
 * overlay is showing; false only in the case that hides the overlay entirely.
 *
 * Deliberately a pure function over plain values — no Context, no Android types — so it
 * is unit-testable without Robolectric or a device.
 *
 * @param airPlayState Current AirPlay protocol state; CONNECTED means a stream is live.
 * @param nowPlaying   Non-null while audio plays with no video (now-playing card).
 * @param photoFrame   Non-null while an AirPlay photo is on screen.
 * @param pin          Non-null while a pairing PIN is displayed and awaiting entry.
 */
fun isSessionActive(
    airPlayState: ProtocolState,
    nowPlaying: NowPlayingInfo?,
    photoFrame: PhotoFrame?,
    pin: String?
): Boolean =
    // A PIN counts as active: it is displayed for the user to read and type, and a
    // screensaver covering it would make pairing impossible.
    pin != null ||
        nowPlaying != null ||
        airPlayState == ProtocolState.CONNECTED ||
        photoFrame != null
