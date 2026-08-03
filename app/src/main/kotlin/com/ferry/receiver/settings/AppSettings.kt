package com.ferry.receiver.settings

/**
 * AppSettings — Immutable data model for all user-configurable Ferry settings.
 *
 * WHY: Centralizing all settings in one data class gives a single source of truth.
 * Any component that needs a setting reads from here. Any component that changes a
 * setting creates a new copy via [copy]. This makes settings changes explicit and
 * easy to test.
 *
 * HOW: Settings are persisted via [SettingsRepository]. Get the current settings
 * from [SettingsRepository.settingsFlow] and update them via [SettingsRepository.update].
 *
 * Example:
 *   // Read settings
 *   val settings = settingsRepository.settingsFlow.first()
 *   if (settings.airPlayEnabled) { ... }
 *
 *   // Change a setting
 *   settingsRepository.update { it.copy(displayName = "My TV") }
 */
data class AppSettings(

    // ─── Display ───────────────────────────────────────────────────────────
    /**
     * The name shown in sender pickers (AirPlay menu on Mac, Cast picker in Chrome, etc.).
     * If empty, the Android device name is used as a fallback.
     * Validated: max 63 characters, must not be blank after trimming.
     */
    val displayName: String = "",

    // ─── Protocols ─────────────────────────────────────────────────────────
    /**
     * Whether the AirPlay 2 receiver is enabled.
     * When false: mDNS advertisement is stopped, RTSP port 7000 is not opened.
     */
    val airPlayEnabled: Boolean = true,

    /**
     * Whether the Miracast (Wi-Fi Display) receiver is enabled.
     * When false: Wi-Fi P2P service advertisement is stopped.
     */
    val miracastEnabled: Boolean = true,

    /**
     * Whether the Google Cast receiver is enabled.
     * On Fire TV (no Google Play Services), this is ignored.
     * When false: Cast SDK is not initialized.
     */
    val castEnabled: Boolean = true,

    // ─── AirPlay specific ──────────────────────────────────────────────────
    /**
     * Whether AirPlay connections require PIN authentication.
     * When true (default): the sender must enter a 4-digit PIN shown on the TV.
     * When false: any device on the local network can mirror without confirmation.
     *
     * Defaults ON. Ferry listens on every interface with no transport authentication of its own, so
     * without this any device that can reach the LAN — including a guest or an untrusted IoT device
     * on the same subnet — can put arbitrary video on the screen.
     */
    val airPlayPinAuthEnabled: Boolean = true,

    /**
     * When true, Ferry withholds the AirPlay video-URL capability bit so senders always mirror the
     * screen instead of handing off a media URL for Ferry to play on its own.
     *
     * Exists because which of the two modes you get is otherwise the *sending app's* decision, made
     * per-app and invisibly: an app playing a plain remote stream pops out into a separate player on
     * the TV with the iPad reduced to a remote, while an app with a custom renderer or DRM it can't
     * delegate simply mirrors. Turning this on makes the behaviour uniform.
     *
     * Defaults OFF, because the video-URL route is the better one when it is available — the TV
     * fetches the stream at full source resolution instead of decoding a re-encode of the sender's
     * screen, and the sender can lock. This trades that away for predictability.
     *
     * Read at receiver startup, so changing it restarts the service
     * (see [com.ferry.receiver.airplay.AirPlayFeatures]).
     */
    val forceScreenMirroring: Boolean = false,

    // ─── Service behavior ──────────────────────────────────────────────────
    /**
     * Whether FerryService starts automatically on device boot.
     * Requires the RECEIVE_BOOT_COMPLETED permission to be effective.
     */
    val startOnBoot: Boolean = false,

    // ─── Developer / Debug ─────────────────────────────────────────────────
    /**
     * Overlays a debug HUD on the streaming screen showing:
     * - Current frames per second
     * - Estimated A/V latency (ms)
     * - Active protocol name
     * Only useful for development and testing.
     */
    val showDebugOverlay: Boolean = false,

    // ─── Video ─────────────────────────────────────────────────────────────
    /**
     * When true, advertise a higher mirroring resolution (1440p) in the AirPlay `/info`
     * `displays` record so macOS renders/encodes the mirror at 2560×1440 instead of 1920×1080.
     * The TV surface is 1080p, so frames are downscaled (sharper text via supersampling) at
     * the cost of more decode work — heavier on low-end SoCs.
     */
    val forceHighResolution: Boolean = false,

    /**
     * When true, accept the mirroring audio stream (type 96, AAC-ELD). EXPERIMENTAL: macOS uses
     * realtime audio clock-sync (RTCP) that isn't fully implemented yet, which can make macOS tear
     * the whole mirror session down after a couple of seconds — so this defaults OFF to keep video
     * mirroring rock-solid. Turn on to experiment with audio.
     */
    val mirrorAudioEnabled: Boolean = true,

    /**
     * When true, scale the mirrored picture to fill the TV, cropping up to
     * [com.ferry.receiver.util.VideoFit.MAX_CROP_FRACTION] of it rather than showing black bars.
     * When false, always show the whole picture and accept the bars.
     *
     * Exists because a source's shape belongs to the sending device and cannot be negotiated: an
     * iPad's screen is 4:3 and a 16:9 TV can only show it with bars, a crop, or distortion. A 4:3
     * source loses a quarter of the screen to side bars under a plain fit; smart fill trades a
     * controlled slice of the top and bottom for most of that back.
     */
    val smartFillEnabled: Boolean = true,

    // ─── Audio ─────────────────────────────────────────────────────────────
    /**
     * Extra playback gain in decibels, 0 (off) to [com.ferry.receiver.util.AudioGain.MAX_BOOST_DB].
     *
     * For sources that are simply quiet — the TV is already turned up and it still isn't enough.
     * Applied via [com.ferry.receiver.util.LoudnessBoost], which compresses rather than plainly
     * scaling, so boosted loud passages don't clip.
     *
     * Defaults to 0: this is a correction for a specific problem, not something every stream wants,
     * and any non-zero value costs some dynamic range.
     */
    val audioBoostDb: Int = 0
) {

    /** Advertised mirroring display size: 2560×1440 when [forceHighResolution], else 1920×1080. */
    val mirrorWidth: Int get() = if (forceHighResolution) 2560 else 1920
    val mirrorHeight: Int get() = if (forceHighResolution) 1440 else 1080

    /**
     * Returns the validated, trimmed display name.
     * If the stored name is blank, returns an empty string so callers
     * can fall back to the system device name.
     */
    val effectiveDisplayName: String
        get() = displayName.trim()

    /**
     * Returns true if at least one protocol is enabled.
     * If all three are disabled, the service has nothing to do.
     */
    val anyProtocolEnabled: Boolean
        get() = airPlayEnabled || miracastEnabled || castEnabled

    companion object {
        /** The default settings instance used on first launch. */
        val DEFAULT = AppSettings()

        /** Maximum allowed length for the display name (mDNS limit). */
        const val DISPLAY_NAME_MAX_LENGTH = 63
    }
}
