package com.ferry.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ferry.receiver.MainActivity
import com.ferry.receiver.R
import android.view.Surface
import com.ferry.receiver.airplay.AirPlayReceiver
import com.ferry.receiver.settings.AppSettings
import com.ferry.receiver.settings.SettingsRepository
import com.ferry.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * FerryService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that Ferry is active.
 *
 * HOW: Bind to this service from [MainActivity] to receive state updates.
 * Use [ServiceController] to send start/stop/restart commands.
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ServiceController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 */
class FerryService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    private val _miracastState = MutableStateFlow(ProtocolState.DISABLED)
    val miracastState: StateFlow<ProtocolState> = _miracastState.asStateFlow()

    private val _castState = MutableStateFlow(ProtocolState.DISABLED)
    val castState: StateFlow<ProtocolState> = _castState.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private val _photoFrame = MutableStateFlow<PhotoFrame?>(null)
    val photoFrame: StateFlow<PhotoFrame?> = _photoFrame.asStateFlow()

    // Non-null while AirPlay audio is playing WITHOUT video — drives the now-playing overlay.
    private val _nowPlaying = MutableStateFlow<com.ferry.receiver.airplay.NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<com.ferry.receiver.airplay.NowPlayingInfo?> = _nowPlaying.asStateFlow()

    // Non-null while a PIN should be shown on screen for SRP pair-setup (PIN access control).
    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    /**
     * Non-null just after Ferry ended a session itself to recover, carrying what the watchdog found.
     * Consumed and cleared by [MainActivity], which puts it on the television.
     */
    private val _sessionNotice = MutableStateFlow<String?>(null)
    val sessionNotice: StateFlow<String?> = _sessionNotice.asStateFlow()

    // Surface provider — supplied by MainActivity after binding (Sprint 5).
    // The lambda captures this field so it always uses the latest provider even if
    // setVideoSurfaceProvider() is called after startAirPlay().
    @Volatile private var videoSurfaceProvider: (() -> Surface?)? = null

    /**
     * Whether [MainActivity] is currently on screen. Written from the Main thread by
     * [onAppForegrounded]/[onAppBackgrounded], read from a coroutine — hence @Volatile.
     */
    @Volatile private var appVisible = false

    // Receiver instances — null when not running
    private var airPlayReceiver: AirPlayReceiver? = null

    /**
     * Miracast and Cast, behind a per-flavor seam. On the Fire TV flavor this is a no-op and
     * neither protocol is compiled into the APK at all — see the firetv [OptionalProtocols] for why
     * both were removed there.
     */
    private var optionalProtocols: OptionalProtocols? = null

    // Settings — read once when starting, re-read on restart
    private lateinit var settingsRepository: SettingsRepository

    /**
     * Keeps the Wi-Fi radio out of power-save while receivers are up. Created in [onCreate] and
     * held for exactly as long as the receivers are running — see [WifiPerformanceLock] for why a
     * receiving-only app is the case the driver's power-save heuristic gets wrong.
     */
    private lateinit var wifiLock: com.ferry.receiver.util.WifiPerformanceLock

    /**
     * Watches for the main thread wedging, which is what a "the whole thing froze and then crashed"
     * report actually is. Runs only while receivers are up — that is the window where a freeze
     * matters and the only one worth spending a heartbeat on. See [MainThreadWatchdog].
     */
    private val mainThreadWatchdog = com.ferry.receiver.util.MainThreadWatchdog { stalledMs, stack ->
        com.ferry.receiver.util.CrashReporter.recordFreeze(stalledMs, stack)
    }

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("FerryService created")
        settingsRepository = SettingsRepository(applicationContext)
        wifiLock = com.ferry.receiver.util.WifiPerformanceLock(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_NOT_STICKY, deliberately. START_STICKY restarts a killed service with a *null*
        // intent, which the branch above treats as "start everything" — so the system could
        // silently resurrect a full receiver with no app on screen and no user action, which is
        // precisely the invisible-advertiser behaviour 6.0.0 exists to remove. The two cases that
        // legitimately want a receiver without an activity are covered explicitly and on purpose:
        // AppSettings.advertiseInBackground keeps it alive when the app backgrounds, and
        // BootReceiver brings it up after a reboot.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The app was swiped away from recents. Cleanly stop all receivers (which closes the RTSP
     * connection so an active mirror ends on the sender too) and stop the service — don't let
     * START_STICKY silently resurrect it as a zombie that keeps advertising/streaming invisibly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("App task removed — stopping receivers + service")
        stopReceivers()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called by [MainActivity] after it binds, to supply the [Surface] for video rendering.
     *
     * The lambda is invoked lazily — only when a stream is actually being started — so it
     * is safe to call this before or after [startAirPlay]. The lambda should return null
     * if the Activity's StreamingScreen is not yet available (e.g., surface not yet created).
     *
     * Call with `{ null }` (or simply don't call) during Activity destruction so we stop
     * holding a reference to the Activity's Surface after the window is gone.
     *
     * @param provider Lambda that returns the current [Surface], or null if unavailable.
     */
    fun setVideoSurfaceProvider(provider: () -> Surface?) {
        videoSurfaceProvider = provider
    }

    /** Called by [MainActivity] from onStart, while bound, once the app is on screen again. */
    fun onAppForegrounded() {
        appVisible = true
    }

    /**
     * Called by [MainActivity] from onStop, while still bound, when the app leaves the screen.
     *
     * Unless the user has opted into background receiving, this ends the session and stops the
     * service — so Ferry stops advertising over mDNS and stops listening on port 7000 the moment
     * it is no longer on screen. Before 6.0.0 nothing did this: the activity stopped the service
     * only when `isFinishing`, and Fire TV's Home button never finishes the activity, so the
     * receiver stayed up indefinitely after the user thought they had closed the app.
     *
     * An active stream is torn down too, rather than exempted. Stopping the receiver closes the
     * RTSP connection, which tells the sender to stop mirroring — the alternative is a session that
     * keeps running against a Surface that no longer exists, which is not a session anyone can see
     * or control. It matches what backing out of the app has always done.
     *
     * The settings read is asynchronous, so [appVisible] is re-checked before acting: the user can
     * return to the app inside that window, and a stop that lands after they are back would kill a
     * receiver that is legitimately wanted.
     */
    fun onAppBackgrounded() {
        appVisible = false
        serviceScope.launch {
            val settings = runCatching { settingsRepository.settingsFlow.first() }.getOrNull()
                ?: AppSettings.DEFAULT
            if (settings.receiveWhenClosed) {
                Logger.i("App backgrounded — background receiving is on, staying up")
                return@launch
            }
            if (appVisible) {
                Logger.d("App returned before the background stop landed — staying up")
                return@launch
            }
            Logger.i("App backgrounded — stopping receivers so Ferry stops advertising")
            stopReceivers()
            stopSelf()
        }
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    /** Clears the recovery notice once the viewer has seen it. */
    fun clearSessionNotice() {
        _sessionNotice.value = null
    }

    fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    override fun onDestroy() {
        Logger.i("FerryService destroying")
        stopAllReceiversInternal()
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay, Miracast, and/or Cast
     * receivers according to the enabled flags.
     */
    private suspend fun startReceivers() {
        val settings = settingsRepository.settingsFlow.first()
        Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, " +
                 "optionalProtocols=${OptionalProtocols.ANY_SUPPORTED}")

        _serviceState.value = ServiceState.Running
        updateNotification(isRunning = true)

        // Before any receiver binds a socket: a receiving-only app is exactly the case Wi-Fi
        // power-save mishandles, and the bursty delivery it causes is indistinguishable from a slow
        // decoder from the couch. Released in [stopAllReceiversInternal].
        wifiLock.acquire()
        mainThreadWatchdog.start()

        if (settings.airPlayEnabled) startAirPlay(settings)

        // The receivers report their own state; nothing is assumed here. Through 5.5.0 this set
        // ADVERTISING optimistically before either receiver had done anything, so a protocol that
        // failed on its very first call still flashed green on the way to red.
        optionalProtocols = OptionalProtocols(applicationContext).also {
            it.start(
                settings = settings,
                onMiracastState = { state -> _miracastState.value = state },
                onCastState = { state -> _castState.value = state },
            )
        }
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        stopAllReceiversInternal()
        _serviceState.value = ServiceState.Stopped
        _activeConnection.value = null
        updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers() {
        Logger.i("Restarting all receivers")
        _serviceState.value = ServiceState.Restarting
        updateNotification(isRunning = false)
        stopAllReceiversInternal()
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // Mirror the debug-overlay setting into the shared stats bus that StreamingScreen reads.
        com.ferry.receiver.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay
        com.ferry.receiver.airplay.StreamStats.smartFillEnabled = settings.smartFillEnabled
        com.ferry.receiver.airplay.StreamStats.audioBoostDb = settings.audioBoostDb

        // Idempotent: a redundant ACTION_START (e.g. the activity being recreated while the
        // foreground service is still alive) must NOT spin up a second AirPlayReceiver competing
        // for port 7000. The existing receiver keeps running and picks up the new Surface via the
        // surfaceProvider. A genuine restart goes through ACTION_RESTART (stop → delay → start).
        if (airPlayReceiver != null) {
            Logger.i("AirPlay receiver already running — skipping duplicate start")
            return
        }
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        airPlayReceiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = settings.mirrorWidth,
            mirrorHeight = settings.mirrorHeight,
            audioEnabled = settings.mirrorAudioEnabled,
            pinAuthEnabled = settings.airPlayPinAuthEnabled,
            forceScreenMirroring = settings.forceScreenMirroring,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When MainActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            onPhotoReceived = { bytes, imageType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType
                )
                updateNotification(isRunning = true)
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            onNowPlayingChanged = { info ->
                _nowPlaying.value = info
            },
            onPinChanged = { pin ->
                _pairingPin.value = pin
            },
            onSessionRecovered = { reason ->
                _sessionNotice.value = reason
            },
            onStateChanged = { state ->
                _airPlayState.value = state
                when (state) {
                    ProtocolState.CONNECTED   -> {
                        _photoFrame.value = null
                        _activeConnection.value =
                            ActiveConnection(pendingSenderName, Protocol.AIRPLAY)
                        updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                        clearServiceError()
                    }
                    ProtocolState.ADVERTISING -> {
                        _activeConnection.value = null
                        updateNotification(isRunning = true)
                        clearServiceError()
                    }
                    ProtocolState.DISABLED    -> {
                        _activeConnection.value = null
                        updateNotification(isRunning = false)
                    }
                    ProtocolState.ERROR       -> {
                        _activeConnection.value = null
                        updateNotification(isRunning = false)
                        // ServiceState.Error has existed since the first commit, is rendered by
                        // HomeFragment, and nothing ever set it — so a receiver that had failed
                        // outright still showed the service badge as "running". AirPlay is the only
                        // protocol whose failure means the service has nothing left to do, which is
                        // what makes it the right one to surface here.
                        _serviceState.value =
                            ServiceState.Error(getString(R.string.service_error_airplay))
                    }
                }
            }
        ).also { it.start() }
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    /**
     * Returns the service badge to Running once AirPlay recovers.
     *
     * The counterpart to setting [ServiceState.Error], and the half that matters: mDNS registration
     * now retries in the background, so an error genuinely does clear on its own, and a badge that
     * could only ever go red would reproduce in miniature the sticky-error bug this release fixes.
     */
    private fun clearServiceError() {
        if (_serviceState.value is ServiceState.Error) {
            _serviceState.value = ServiceState.Running
        }
    }

    private fun stopAllReceiversInternal() {
        try { airPlayReceiver?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        try { optionalProtocols?.stop() } catch (e: Exception) { Logger.e("Optional protocol stop error", e) }
        // Nothing is listening any more, so stop keeping the radio awake for it. `lateinit`, and
        // onDestroy can land before onCreate on a service that failed to start, hence the guard.
        if (::wifiLock.isInitialized) wifiLock.release()
        mainThreadWatchdog.stop()
        airPlayReceiver = null
        optionalProtocols = null
        _airPlayState.value = ProtocolState.DISABLED
        _miracastState.value = ProtocolState.DISABLED
        _castState.value = ProtocolState.DISABLED
        _photoFrame.value = null
        _nowPlaying.value = null
        _pairingPin.value = null
        _sessionNotice.value = null
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // LOW: no sound, minimal visual interruption
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    private fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FerryService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, FerryService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationContentText ?: getString(statusText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, getString(R.string.action_restart), restartIntent)
            .build()
    }

    private fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null) {
        val contentText = streamingSenderName?.let {
            getString(R.string.notification_status_streaming, it)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning, contentText))
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [FerryService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): FerryService = this@FerryService
    }

    companion object {
        const val CHANNEL_ID      = "ferry_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "com.ferry.receiver.action.START"
        const val ACTION_STOP     = "com.ferry.receiver.action.STOP"
        const val ACTION_RESTART  = "com.ferry.receiver.action.RESTART"
    }
}
