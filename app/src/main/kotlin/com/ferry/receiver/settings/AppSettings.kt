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
     * When true: the sender must enter a 4-digit PIN shown on the TV.
     * When false (default): any device on the local network can mirror without confirmation.
     *
     * **Defaults OFF, and that is an access-control decision, not an oversight.** Ferry listens on
     * every interface and the AirPlay protocol gives it no transport authentication of its own, so
     * with this off, anything that can reach the subnet — a guest's phone, an untrusted IoT device,
     * anyone on the same Wi-Fi — can put arbitrary video and audio on the screen without touching
     * the TV. The protection in that configuration is the network perimeter and nothing else.
     *
     * It defaults off because the alternative costs every connection a PIN round-trip on a remote
     * control, on a device that is usually on a home network the owner already trusts. That is the
     * right trade for that setting, and the wrong one on shared, guest, or open Wi-Fi — where this
     * should be turned on. See SECURITY.md.
     *
     * Turning it on also gates pairing behind the SRP flow with a 10-attempt persistent lockout
     * (MAX_PAIR_ATTEMPTS in RtspHandler).
     */
    val airPlayPinAuthEnabled: Boolean = false,

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
     * Whether Ferry keeps receiving — advertising over mDNS and listening on port 7000 — while the
     * app itself is closed.
     *
     * **Defaults OFF, and unlike most defaults here that is a security decision.** Through 5.5.0
     * there was no choice: the receiver simply stayed up. `MainActivity` stopped the service only
     * when `isFinishing`, and on Fire TV the Home button produces `onStop` without ever finishing
     * the activity, so Ferry kept announcing itself to the network indefinitely after the user
     * believed they had closed it. Combined with [airPlayPinAuthEnabled] defaulting to false, that
     * left an invisible, unauthenticated receiver on the LAN — anything that could reach the subnet
     * could put video and audio on the TV, with nothing on screen to suggest Ferry was still
     * listening.
     *
     * Off, the attack surface exists only while the user is looking at the app. On, Ferry behaves
     * as it did before, which is the right choice for a TV that is meant to be castable at any time
     * on a network its owner trusts — but it is now a choice, made deliberately.
     *
     * [startOnBoot] implies this; see [receiveWhenClosed].
     */
    val advertiseInBackground: Boolean = false,

    /**
     * Whether FerryService starts automatically on device boot.
     * Requires the RECEIVE_BOOT_COMPLETED permission to be effective.
     *
     * Implies [advertiseInBackground] — starting headless at boot is by definition receiving with
     * no app open, so honouring one without the other would produce a service that starts on boot
     * and then immediately stops itself. See [receiveWhenClosed].
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
     * When true, advertise **1280×720** instead of 1920×1080, so the sender encodes a quarter fewer
     * pixels than 1080p and less than a fifth of 1440p.
     *
     * The only setting in Ferry that reduces work at the *source*, which is what makes it the
     * strongest performance lever available. Everything else here tunes how the receiver copes with
     * what it is sent; this changes what it is sent. Fewer pixels means a lower bitrate over the
     * Wi-Fi, fewer macroblocks to decode, and less to push to the panel — all three at once, and
     * the network half matters most on a link that is already marginal, because it is the one Ferry
     * cannot otherwise do anything about.
     *
     * Off by default. 1080p is the right default for a 1080p panel and looks better; this is for
     * when smooth matters more than sharp, which on a mirrored screen at couch distance is a trade
     * most people take without noticing what they gave up.
     *
     * Takes precedence over [forceHighResolution] — see [mirrorHeight]. Both are exposed as plain
     * toggles rather than one three-way control because that is what every other row on the screen
     * is, and the UI keeps them mutually exclusive.
     */
    val forceLowResolution: Boolean = false,

    /**
     * When true, accept the mirroring audio stream (type 96, AAC-ELD).
     *
     * **Defaults ON.** This doc comment claimed the opposite — "defaults OFF to keep video
     * mirroring rock-solid" — from the initial commit through 5.5.0, while the value beside it was
     * `true` the entire time. Corrected here to what the code has always done, not the other way
     * round: mirror audio has been on in every shipped build, and the audio work that followed
     * (per-path volume in 3.1.0, the compressing boost in [audioBoostDb]) was all built and used
     * against it, so "on" is the behaviour that has actually been exercised on hardware.
     *
     * The warning the old comment carried is kept, because it is a real failure mode rather than a
     * claim about the default: macOS drives mirroring audio with realtime RTCP clock-sync that
     * Ferry does not fully implement, and a sender that gives up on the sync can tear down the
     * **whole** mirror session — video included — seconds after it starts. If a Mac connects and
     * then drops repeatedly, turn this off and see whether video alone is stable; that is the
     * single most useful thing to know when diagnosing it.
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

    /**
     * Advertised mirroring display size: 1280×720, 1920×1080 (default) or 2560×1440.
     *
     * [forceLowResolution] wins if both it and [forceHighResolution] are somehow set. The UI clears
     * one when the other is turned on, so that should not arise — but a settings file written by an
     * older build, or edited by hand, can still produce it, and the safe reading of "the user asked
     * for less work" is to give them less work. Resolving it the other way would silently hand the
     * heaviest setting to someone who explicitly asked for the lightest.
     */
    val mirrorWidth: Int get() = when {
        forceLowResolution -> 1280
        forceHighResolution -> 2560
        else -> 1920
    }

    val mirrorHeight: Int get() = when {
        forceLowResolution -> 720
        forceHighResolution -> 1440
        else -> 1080
    }

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

    /**
     * Whether the receiver should keep running once the app is no longer on screen.
     *
     * The single question `MainActivity` actually needs answered when it backgrounds, and the
     * reason it is derived rather than stored: [startOnBoot] would otherwise be a toggle that
     * appears to work and silently does not, since a service started at boot with no activity
     * would shut itself down moments later.
     */
    val receiveWhenClosed: Boolean
        get() = advertiseInBackground || startOnBoot

    companion object {
        /** The default settings instance used on first launch. */
        val DEFAULT = AppSettings()

        /** Maximum allowed length for the display name (mDNS limit). */
        const val DISPLAY_NAME_MAX_LENGTH = 63
    }
}
