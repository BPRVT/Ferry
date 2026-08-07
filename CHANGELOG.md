# Changelog

All notable changes to Ferry will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [7.1.0] - 2026-08-06

Reported straight off 7.0.0: it froze completely and then crashed. And two
observations that were worth more than the crash itself — **stopping and restarting
the share acts like a fresh slate**, and the audio boost is on by default at +12 dB.

### Fixed

- **Ferry had no crash handling of any kind.** A freeze followed by a crash is the
  signature of an ANR, and Android writes that evidence to logcat and `/data/anr/` —
  two places nobody can reach on a TV stick with no adb. So the most important failure
  Ferry has was also the one that left the least behind, and every fix aimed at it,
  including the ones in 7.0.0, was reasoned rather than diagnosed.

  `CrashReporter` now catches the uncaught exception and writes the stack **plus a
  snapshot of what the video and audio pipeline were doing** to app-private storage —
  the stack says where it died, the counters say what it was in the middle of, and for
  this codebase the second has repeatedly been the more useful half. `MainThreadWatchdog`
  posts a heartbeat to the main thread every second and, after four seconds without an
  answer, captures **that thread's** stack while it is still stuck. `DiagnosticScreen`
  puts whichever it was on the television at the next launch.

  Nothing is uploaded anywhere. The report is app-private and overwritten by the next one.

- **The audio boost retried itself ~92 times a second whenever it failed to attach.**
  `LoudnessBoost.sync` is called once per audio packet, on the explicit promise that it
  costs nothing unless the request changed — and its early return compares the applied
  gain against the requested one, which a failure leaves permanently unequal. So a device
  that refused the effect got a `LoudnessEnhancer` construction, a binder call into
  audioserver, an exception and a log line for every packet, for the rest of the session,
  on the one thread where a stall is audible.

  Unreachable at 0 dB, because there the two agree. **Only users who turned the boost on
  were ever exposed to it.** The gain itself costs nothing — it runs in the audio HAL, not
  on Ferry's playback thread.

- **The overlay reported the resolution Ferry asked for, not the one it got.** The
  advertised size is a request in the AirPlay `/info` record and the sender decides whether
  to honour it, so a resolution setting the sender declined still read as though it had
  applied. It now shows what is actually being decoded, and names the disagreement when
  there is one: `1920x1080 (asked 1280x720)`.

### Added

- **Automatic mid-session recovery.** Ferry now watches the rate at which frames are
  actually destroyed — shed at the queue or refused by the decoder — and escalates when it
  stays bad: a decoder rebuild after five seconds above 20% loss, and after fifteen, ending
  the RTSP session so the sender re-establishes it.

  That second step is the automatic equivalent of stopping and restarting the share by
  hand, which is exactly the workaround that was reported to work. Rate-limited to one a
  minute, so a link that is genuinely too poor settles into a degraded picture rather than a
  reconnect loop — which would be worse than the problem. Render skips are deliberately not
  counted as loss: those are the pacing rule working correctly, and confusing the two would
  recycle healthy sessions at high frame rates.

- **A 720p option** (Settings → *Smoother playback*). Off by default; 1080p stays the
  default and looks better.

  The only setting in Ferry that reduces work at the **source**. Everything else tunes how
  the receiver copes with what it is sent; this changes what it is sent — a quarter fewer
  pixels than 1080p means a lower bitrate over the Wi-Fi, fewer macroblocks to decode and
  less to push to the panel, all at once. The network half matters most on a marginal link,
  because it is the one Ferry can otherwise do nothing about. Mutually exclusive with the
  1440p option.

### Changed

- The MediaCodec callback thread runs at urgent-display priority. It is not a bookkeeping
  thread — it is the thread that renders every frame Ferry displays, and a buffer it fails
  to hand back promptly is a buffer the codec cannot reuse, which is the output-side jam
  that starves the input side and destroys frames.
- The audio receive path deduplicates **before** copying. macOS sends every realtime-audio
  packet 2–3× for redundancy, so two thirds of those copies existed only to be discarded a
  line later. Also reuses the PCM staging buffer and `BufferInfo` instead of allocating both
  per decoded frame.
- The frame pool added in 7.0.0 is bounded at 512 KB a buffer. Without a ceiling, one freak
  oversized frame permanently re-cut all eight buffers to its size.

---

## [7.0.0] - 2026-08-06

Every release up to here fixed something that broke. This one fixes something that
*wore out*.

Reported from hardware: Ferry is good for about a day, and by the second day the
picture is laggy and never catches up — with a Wi-Fi link that is admittedly not
great, but an iPad on the same network playing the same content at full speed. And
one more observation, which turned out to be the whole diagnosis: reinstalling the
app makes it "like new" every time.

### Fixed

- **Ferry got slower the longer it had been running.** The reinstall was the clue,
  and it is not a cache — Ferry persists almost nothing, a handful of preferences
  and a small settings store, none of which could slow anything down. What a
  reinstall actually does is **restart the process**. A force-stop does the same.

  Every `start*` in `AirPlayReceiver` assigned straight over its field —
  `mirrorServer`, `audioServer`, `ntpClient`, `eventSocket`, `videoDecoder`,
  `audioPlayer` — on the assumption that a SETUP only ever arrives on a fresh
  session. It does not. Senders re-SETUP streams on a *live* session; that is the
  entire point of the dynamic add/remove path Ferry already supports, and it is
  what happens every time audio stops and starts again. A control connection that
  drops without a TEARDOWN is likewise followed by one that begins again at the key
  exchange. **Dropping the reference does not stop the object.**

  The threads are the expensive part, and the reason this reads as a performance
  bug rather than a memory one. All of those loops are `Dispatchers.IO` coroutines
  parked inside *blocking* socket calls, and `Dispatchers.IO` is capped at 64
  threads. Cancelling a scope cannot reclaim them — cancellation is cooperative,
  and a thread sitting in `accept()` never checks. So every orphan permanently
  removes capacity from the shared pool, and once enough have accumulated, starting
  a cast means waiting on threads that are never coming back. The video reader and
  decoder are drawn from that same pool.

  The orphaned watchdogs made it worse than merely wasteful. Each one went on
  polling `isStreamDead` on a dead connection and calling `endActiveSession()` — on
  the session that had *replaced* it.

  Ferry now stops what it is replacing, at every one of those call sites.

- **A backgrounded Ferry rebuilt its decoder every few seconds, forever.** With
  background receiving on and a cast still live, the stall watchdog's rule — frames
  arriving, nothing reaching the screen — is not a fault when there is no Surface
  to reach. It is the definition of the situation. It matched on every tick, and
  each match was a decoder rebuild counted on the overlay as a recovery from a
  stall that was never happening.

### Changed

- **The video and audio paths own their threads.** Three each, named `FerryMirror`
  and `FerryAudio`, at display and audio priority, shut down with the server that
  owns them. Two reasons, in order of importance. Isolation: the video path can no
  longer be starved by anything else in the process that parks a shared IO thread,
  so a future oversight costs a bounded pool that dies with `stop()` rather than
  shared capacity that does not. Priority: these are display-deadline threads, and
  on a shared pool their priority cannot be raised without leaking it to whatever
  unrelated work runs there next.

  They are shut down with `shutdown()` and never `shutdownNow()` — an interrupt
  would land in the middle of the decoder-release path, which is the documented
  route to a native SIGABRT out of libstagefright.

- **Decrypted frames come from a pool instead of the allocator.** `cipher.update()`
  returned a freshly allocated array for every frame: at 1080p60, roughly 60 arrays
  a second of tens to hundreds of kilobytes — several megabytes per second of
  short-lived garbage on a heap with very little headroom. The cost is not the
  allocation, it is the collection, because every GC steals CPU from the decoder
  thread, and a decoder thread that misses its deadline is exactly what turns into
  a dropped frame and a corrupt picture. The reader already reused its *encrypted*
  buffer for this reason; the decrypted side was still allocating.

  This is why `avccToAnnexBInPlace` now takes an explicit length. A pooled buffer is
  sized to the largest frame seen so far, so it is routinely longer than the frame
  in it and the tail still holds the previous frame's bytes. Walking to `data.size`
  would find that older frame's perfectly well-formed length prefix past the end of
  this one and splice a NAL made of stale video onto a good frame — silently, with
  nothing counted and nothing thrown. Three tests fail if that bound reverts.

- **The hardware decoder is handed back when there is no Surface to draw to.** A
  MediaCodec is not an in-process object; it is one of a handful of AVC decoder
  instances the whole device has, and on a stick that handful is very small.
  Holding one while Ferry has nothing to render means the next app to want a
  decoder — or Ferry's own next session — contends for hardware that is idle.

- **Wi-Fi power-save is held off while receiving.** The driver's heuristic reads a
  receive-only app as idle, because it is not transmitting, and sleeps the radio
  between beacon intervals. That converts a steady stream into bursts: a frame
  arriving late and then three at once, compounding on a marginal link. It is also
  why a sender on the same network can look perfectly fine while the TV does not —
  an iPad playing the video is transmitting constantly and is never a candidate for
  this. Ferry now takes a `WIFI_MODE_FULL_LOW_LATENCY` lock (`HIGH_PERF` below API
  29) for exactly as long as its receivers are up. Requires the `WAKE_LOCK`
  permission, which is what `acquireWifiLock` is gated on; nothing here keeps the
  CPU or the screen awake.

- **Overlay counters describe the current session.** `StreamStats.resetStreams()`
  existed and was never called, so `SHOW`, `skip` and the watchdog line were running
  totals across every cast since the app started — and the entire purpose of those
  numbers is to answer what is happening right now.

### Note

The diagnosis above is reasoned from the code and cannot be confirmed without the
device. The experiment that settles it costs nothing: **next time it degrades,
force-stop Ferry rather than reinstalling.** If that restores it, this release is
aimed correctly. If only a full reboot does, something is leaking device-wide and
the decoder instances are the first suspect. If neither helps, it is the network,
and none of this is the story.

---

## [6.8.0] - 2026-08-05

6.7.0 made the video recover. This makes the audio recover with it.

### Fixed

- **Audio latency never came back down.** Reported after a successful 6.7.0 link
  recovery: the picture froze for a second, fixed itself — and the audio queue sat
  near capacity from then on, permanently behind the video.

  The jitter queue was a ratchet. The producer delivers at the sender's real-time
  rate; the consumer writes with `WRITE_BLOCKING`, which paces at the DAC's
  real-time rate. Both run at real time, so **whatever depth the queue reaches, it
  keeps** — and that depth *is* how far audio lags video. A burst during the
  recovery pushed it to 27 of 32 packets, roughly 290 ms, with no path back.
  Bounding the queue (which is all `AUDIO_QUEUE_CAPACITY` ever did) stops latency
  growing without limit; it does nothing about latency already accumulated.

  Ferry now drains it, by playing about 2% fast until the queue is back to target.
  A 290 ms backlog clears in roughly 12 seconds.

  **Speeding up rather than dropping packets is the whole point.** Dropping would
  clear it instantly and is the usual meaning of "fix the latency" — but each
  packet is ~10 ms of audio, so a 290 ms backlog means one large hole or a rapid
  series of clicks. A 2% resample shifts pitch by about 34 cents: imperceptible on
  speech, marginal on sustained musical notes, and gone once the backlog is. Sync
  recovering quietly over ten seconds is not something anyone notices happening,
  which is what was asked for.

  The controller has a deliberately wide band — engage at 12 packets (~130 ms),
  disengage at 4 (~45 ms). Without that hysteresis a queue hovering near one
  threshold would toggle the playback rate ~90 times a second, and continuous
  pitch modulation is an audible warble. That band is mutation-tested: collapsing
  it to a single threshold fails two tests.

### Changed

- The overlay shows `sync↓` on the AUDIO line while a drain is in progress.
  Watching `q` fall beside it is what confirms the controller is working — and
  seeing it lit constantly would mean the queue is refilling as fast as it drains,
  which is a different problem worth knowing about.

### Note

- This is a general fix, not a recovery-specific one. Any burst that deepens the
  queue — a Wi-Fi stumble, a resend storm, a codec hiccup — used to leave
  permanent lag behind it. All of them now drain.

---

## [6.7.0] - 2026-08-05

The freeze was never a decoder problem. 6.6.0's overlay proved it in one photo.

### Fixed

- **A dropped mirror video connection froze the picture permanently.** The
  reported failure, now with numbers: `in 38s`, `last 38s`, `DEC ok`,
  `drops 4`, `qdrop 0%`, `q 2/16`. A healthy decoder that had dropped four frames
  all session — and nothing to decode. The video stream had gone and Ferry had no
  way to notice, report, or recover, while the iPad went on playing and the
  session still said CONNECTED to everyone.

  One line caused it. `MirrorStreamServer.runReader` set `running = false` when
  the sender's data connection ended — but `running` also terminates the decoder
  thread *and* the watchdog. **The event that needed reacting to killed everything
  capable of reacting to it.** `running` now means only "this server is shutting
  down", which is [stop]'s to say; a sender going away is recorded separately.

  With the pipeline still alive, the watchdog can act: once the data connection
  has been gone for two seconds it ends the RTSP session, so the sender stops
  believing it is still mirroring and re-establishes properly.

  Ferry deliberately does **not** try to re-accept on the same socket. The AES-CTR
  keystream is bound to the connection that died, so a sender reconnecting to it
  would decrypt to garbage — visibly worse than the freeze. A real recovery needs
  a fresh SETUP with fresh keys, which is exactly what ending the session asks for.

  The trigger is **the socket having actually closed**, and nothing else. The
  obvious alternative — "no frames for N seconds" — would have been a worse bug
  than the one being fixed: iOS sends frames only when the screen changes, so
  every time you paused a video it would have torn down a working cast.

### Changed

- The overlay distinguishes "nothing is arriving" from "Ferry is slow". The
  arrival field reads `down 38s` instead of `in 38s` once the sender's connection
  has gone.

- **Displayed frame rate decays to 0 when the stream stops.** `videoFps` is only
  recomputed every 300 frames, so a dead stream kept showing whatever rate it had
  last managed — a frozen picture beside a confident `31fps`. That reading is what
  talked the 6.6.0 investigation out of the correct diagnosis once already.

### Note

- **On the audio numbers in that same photo:** `AUDIO on q 27 dup 39%` — the audio
  buffer nearly full and 39% of packets arriving as duplicates or retransmits.
  That is a Wi-Fi link working hard, and the likely reason the video stream (TCP)
  was reset while audio (its own socket, with redundancy) limped on. Ferry's job
  is to survive it, which is what this release does; a link that bad is still
  worth fixing at the router.

- **Two guards in this release are mutation-tested**, because both would cause
  real damage if wrong: tearing down a paused sender's session, and firing before
  the sender has ever connected. The first attempt at the paused-sender test
  passed even with the guard deleted — a hole that mutation testing found and
  reading did not.

---

## [6.6.0] - 2026-08-05

A frozen picture used to be permanent, and the overlay could not explain why.
Both fixed.

### Fixed

- **A frozen picture now recovers on its own.** Reported from hardware on 6.0.0:
  the iPad kept playing, TV audio kept playing, and the video sat on a single
  frame until the cast was stopped and restarted by hand.

  Audio survives because it is a separate server on a separate socket. Video did
  not, because **nothing in the video path was watching itself** — a decoder that
  stopped producing simply stayed stopped, while the session went on reporting
  CONNECTED to both the UI and the sender. There was no recovery and no signal;
  the only way out was the remote.

  A watchdog now notices when frames are arriving but none is reaching the screen,
  and forces the decoder to rebuild. It deliberately does *not* tear the session
  down — a rebuild is cheap and recoverable, dropping a live session on a false
  positive is not, and the detector is new.

  The condition is deliberately narrow: **frames arriving** *and* **nothing shown**.
  Either half alone would be wrong. iOS sends frames only when something changes,
  so a paused video or a still menu legitimately shows nothing for minutes — a
  watchdog reading only "nothing shown lately" would rebuild a healthy decoder on
  a timer forever. The deadline also sits above the keyframe-resync give-up, so a
  resync that is working as designed is never aborted.

### Changed

- **The debug overlay reports pipeline *state*, not just tallies.** Every number
  on it counted events, which describes throughput — and a freeze is the absence
  of activity. Diagnosing the 6.0.0 freeze meant inferring state from which
  counters had stopped moving, which produced two wrong theories before the right
  one.

  It now shows:
  - `DEC ok` / `none` / `rebuild xN` — whether a decoder exists at all, which is
    the single fact that would have ended that investigation immediately
  - `in 0.4s` and `last 0.4s` — how long since a frame **arrived** and since one
    reached the **screen**. Read together they split every freeze in one glance:
    both stale means nothing is arriving (the sender or the connection stopped);
    arrival fresh and shown stale means frames are coming in and dying inside
    Ferry.
  - `SHOW 5231` — frames actually displayed. Ferry counted every way a frame could
    fail and had no count of success, so "is anything working at all?" was not a
    question the HUD could answer.
  - `q 1/16` rather than a bare depth, so a full queue reads as full.

  Still five lines. The overlay sits on top of live video and its current size
  reads well, so the state fields displaced tallies rather than growing the box.

- **The watchdog reports itself on screen**, as a sixth line that appears only
  once it has fired: `WATCH x2  decoder missing 12s ago`.

  This is deliberate, and it is why the recovery does not simply hide the bug.
  Ferry runs on a TV stick with no adb access, so anything that exists only in
  logcat may as well not exist — and a watchdog that silently saves the session
  six times a night would otherwise destroy the evidence of what it was saving it
  from. The reason names the state it *found* ("decoder missing" vs "decoder
  stuck"), because those are different bugs.

### Note

- **Reading the overlay for a frozen picture:**
  - `in` fresh, `last` stale → frames arriving, dying inside Ferry. Look at `DEC`.
  - `in` stale, `last` stale → nothing arriving. The sender or the connection
    stopped; the watchdog cannot help and correctly will not try.
  - `DEC none` or `rebuild xN` → there is no decoder to give frames to.
  - `q 16/16` → nothing is draining the queue.
  - `WATCH` present → it wedged and recovered. The reason is the bug worth
    chasing, even though the picture came back.

---

## [6.5.0] - 2026-08-05

The corruption fix. 6.0.0 fixed the freezes; this fixes the smeared, blocky
picture that came with them — a different bug, in the opposite half of the
pipeline from where every previous attempt looked.

### Fixed

- **Mirroring corruption: blocky, smeared regions that persist for seconds.**
  Ferry showed every frame it decoded, which sounds like the safe choice and is
  the opposite.

  `releaseOutputBuffer(index, true)` hands a decoded frame to the display's
  BufferQueue, which holds about three, and the codec does not get that buffer
  back until the display has consumed it. Show frames faster than the panel
  refreshes and the queue saturates. A codec with no free output buffer stops
  decoding, and a codec that has stopped decoding stops handing back **input**
  buffers — so `decodeNalUnit` found none free and destroyed the frame.

  **The jam was on the output side and the loss happened on the input side.**
  That is why every previous attempt aimed at the wrong thing: 5.5.0 lengthened
  the input wait, but no wait helps when the buffer cannot be freed until the
  display releases it, and 6.0.0 capped that wait so it could not overflow the
  queue. Both were real bugs. Neither was this one.

  Ferry now shows a frame only once the previous one has had its time on screen,
  and hands back anything earlier without showing it. The asymmetry is the whole
  argument:

  - **Not showing a decoded frame** costs one frame nobody can see. It is still
    decoded, so it still serves as a reference for everything after it.
  - **Not decoding a frame** breaks the reference chain, and the picture stays
    visibly wrong until the sender's next IDR — ~10 seconds on iOS, longer on a
    static screen.

  Ferry was paying the second cost to avoid the first.

  Pacing is against the panel's real refresh rate, read from the display rather
  than assumed, so 50 Hz sets are handled correctly and a nonsense reading is
  clamped instead of blanking the picture.

  At 24 fps into a 60 Hz panel this never triggers. It triggers on bursts —
  which is exactly when the picture was breaking.

### Changed

- The debug overlay gains a line: `SHOW skipped N   panel NNHz`. Reading it
  against `DEC dropped` on the line above is the point — skips climbing while
  drops stay flat is the trade working as intended.

### Note

- **How this was found.** Reported from hardware as glitching that tracked the
  frame rate: `DEC dropped` stepping 1–4 at a time, every time an iPad mirror
  rose from 24 fps toward ~59 on a 60 Hz TV, with decode load nowhere near the
  limit. Two things followed from that. Bursts of 1–4 meant a codec briefly
  *blocked*, not a codec too slow. And 1080p at 30 fps being enough to trigger it
  ruled out decode throughput entirely — which is what pointed at the display
  side.

- **What to expect on the overlay:** `SHOW skipped` should rise during high-frame-rate
  stretches while `DEC dropped` stays put. If `DEC dropped` still climbs, the
  BufferQueue is not the constraint and the next suspect is mirror audio
  competing for the same resources — turn it off and compare.

---

## [6.0.0] - 2026-08-05

A cleanup release, from four things reported off a Fire TV running 5.5.0. Three
of the four turned out not to be the bug they looked like from the couch.

### Fixed

- **The freeze cascade — mirroring lagging, glitching, and hanging for seconds at
  a time.** A regression introduced by 5.5.0's own change, and the reason this
  release exists.

  5.5.0 raised the wait for a free decoder input slot from 4 ms to 16 ms, and
  5.0.1 had already given keyframes 100 ms. Both waits block
  `MirrorStreamServer`'s decoder thread — which is the *only* thread draining the
  16-frame queue. So while it waited, the reader kept enqueuing and nothing was
  removed. Long enough, and the queue overflowed; the drop policy then had to
  shed a referenced frame, which armed a keyframe resync, which stopped the
  picture for up to three seconds.

  The keyframe case was worse and self-sustaining. A 100 ms block is about six
  frames of reader output, so the overflow landed *during the IDR's own decode
  call*. The guard added in 5.0.2 then correctly refused to let that IDR clear
  the resync — meaning **the keyframe wait caused the overflow that invalidated
  the keyframe it was waiting for**. Three seconds frozen, a moment of motion,
  three seconds frozen. Which reads as a TV that has locked up.

  5.5.0's reasoning — "with one frame queued, the extra 12 ms delays nothing
  behind it" — was correct at queue depth 1, and nothing whatsoever held the
  depth there. That guard now exists: the wait is capped by
  `MirrorStreamServer.waitBudgetMs`, derived from how much queue headroom is
  actually left. Empty queue, full 100 ms as intended. Queue nearly full, no wait
  at all, because at that depth there is nothing left to spend.

  Worth naming plainly: each individual piece of this — the 16 ms wait, the
  100 ms keyframe wait, the arming guard, the 3-second give-up — was a correct
  fix for the bug in front of it across 5.0.0–5.5.0. The loop only existed in
  their combination.

- **One codec hiccup froze the picture for the rest of the session.**
  `MirrorStreamServer.runDecoder`'s exception handler sat *outside* its loop, and
  `VideoDecoder.initialize` can throw unchecked for transient reasons — another
  app holding the hardware decoder, a surface torn down mid-configure. A single
  throw exited the loop for good, while the reader kept filling a queue nobody
  drained. Every frame dropped, picture frozen, no way back short of the sender
  reconnecting.

  The handler is now inside the loop; the decoder is rebuilt from the cached
  SPS/PPS, rate-limited to one attempt per second. The "decoder unhealthy" path
  no longer clears the cached parameter sets either — it used to wait for the
  sender to volunteer a fresh config packet, which it is under no obligation to
  send.

  `VideoDecoder.initialize` also leaked a `HandlerThread` on every failure,
  because the half-built decoder was discarded without `release()` ever running.

- **The error that stuck after manually stopping a cast — while casting kept
  working.** That last part was the clue.

  `AirPlayReceiver.onStreamingStopped` restarted mDNS at the end of *every*
  session. Nothing had ever unregistered it — the registration survives a session
  untouched — so the restart re-advertised something that was never withdrawn,
  and raced an asynchronous `unregisterService` against an immediate
  re-registration of the same two names. When one of the two lost that race,
  `MdnsService` emitted ERROR and had no way back: it emitted ADVERTISING only
  when a counter reached 2, and that counter only ever counted up. Meanwhile the
  RTSP server on port 7000 is entirely independent of mDNS and carried on
  listening, and senders cache the record. Hence: permanent error on screen,
  casting still fine.

  The needless restart is gone. Registration state is now tracked per service and
  recomputed rather than tallied, so it is reversible; failures clear the listener
  (which used to leak one per cycle), retry with backoff, and recover to
  ADVERTISING when they succeed.

### Changed

- **Ferry no longer advertises itself while the app is closed.** Reported as a
  safety concern, and it was a real one.

  `MainActivity` stopped the service only when `isFinishing`, and Fire TV's Home
  button produces `onStop` without ever finishing the activity — so Ferry kept
  announcing itself over mDNS and listening on port 7000 indefinitely after the
  user believed they had closed it. With PIN pairing off by default, that left an
  invisible, unauthenticated receiver on the LAN: anything that could reach the
  subnet could put video and audio on the TV, with nothing on screen to suggest
  Ferry was still listening.

  Receiving is now scoped to the app being open. **Settings → Keep receiving when
  closed** restores the old behaviour deliberately; "Start on boot" implies it.

  `START_STICKY` is now `START_NOT_STICKY` for the same reason — it restarted a
  killed service with a null intent, which the service treated as "start
  everything", silently resurrecting a receiver with no app on screen and no user
  action.

- **Miracast and Cast are gone from the Fire TV build.** Neither ever worked
  there, and both were enabled by default.

  Cast *cannot* work: Fire TV has no Google Play Services, so the receiver was a
  no-op stub that reported DISABLED — while still showing a toggle and a card
  that could never do anything. Miracast was worse than inert: it gated on
  `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES`, and Ferry never requested
  either at runtime (only `POST_NOTIFICATIONS`), so the check failed on every Fire
  TV on every boot and the receiver emitted ERROR without ever advertising or
  opening port 7236. A permanent red card for a protocol that never ran.

  The Fire TV APK now contains no Miracast or Cast code at all, and drops
  `CHANGE_WIFI_STATE`, both capped location permissions, and
  `NEARBY_WIFI_DEVICES` — permissions the app asked for and could not use.

  **The Google TV flavor is unchanged**, including its real Cast Connect
  implementation. Both protocols live behind a per-flavor `OptionalProtocols`
  seam.

- Protocol cards no longer flash green before going red: `ADVERTISING` was set
  optimistically before either optional receiver had done anything.

- `ServiceState.Error` has existed since the first commit, is rendered by the
  home screen, and nothing ever set it — so a receiver that had failed outright
  still showed the service badge as "running". It is now set when AirPlay fails,
  and cleared when it recovers.

- `ServiceController.stop()` could throw. It used `startService`, which is
  illegal from the background on API 26+ — and its main caller is
  `MainActivity.onDestroy`, where the restriction is most likely to apply.

### Note

- **`mirrorAudioEnabled`'s documentation contradicted its value, and had since
  the first commit.** The comment said "defaults OFF to keep video mirroring
  rock-solid"; the value beside it was `true` in every shipped build. Corrected
  to match the code rather than the other way round — mirror audio has been on
  throughout, and the audio work built on top of it (per-path volume in 3.1.0,
  the compressing boost in 3.1.0) was all exercised against it.

  The warning it carried is kept, because it describes a real failure mode rather
  than a default: macOS drives mirroring audio with realtime RTCP clock-sync that
  Ferry does not fully implement, and a sender that gives up on it can tear down
  the whole mirror session, video included. **If a Mac connects then drops
  repeatedly, turn mirror audio off and see whether video alone is stable** —
  that is the single most useful thing to know when diagnosing it.

- **How to tell whether the freeze fix worked:** the multi-second freezes should
  be gone outright. With the debug overlay on, `keyframeWaits` is the number to
  watch — each one was a resync that stopped the picture for up to three seconds.
  If freezes persist while `keyframeWaits` stays near zero, the cause is
  elsewhere and the next suspect is mirror audio, per the note above.

---

## [5.5.0] - 2026-08-03

### Changed

- **Ordinary frames now get a full frame interval to reach the decoder, not a
  quarter of one.** `INPUT_BUFFER_WAIT_MS` goes from 4 ms to 16 ms.

  When a frame is ready, Ferry hands it to the hardware decoder, which has a
  small pool of slots to receive it. If they are all momentarily busy, Ferry
  waits — and on expiry destroys the frame. Frames arrive about every 17 ms at
  58 fps, so the old deadline gave up after roughly a quarter of the available
  time.

  A destroyed frame is expensive. H.264 frames are mostly deltas, so losing one
  breaks prediction for everything after it until the sender's next IDR, about
  ten seconds away on iOS. One dropped frame is one visible glitch lasting until
  a keyframe rescues it.

  The 4 ms came from a sound rule — drop promptly rather than stall the decoder
  thread while frames pile up behind. What settled it was measurement rather than
  argument: a full episode of real mirroring reported queue depth of **1 out of
  16** essentially throughout, and **0%** queue-level drops. There is no backlog
  to protect. The deadline was destroying frames to save time nothing else wanted
  — 71 of them in roughly 76,000.

  The hardware is rarely busy for long; it is busy for a moment. 16 ms should
  ride out most of those moments where 4 ms did not.

  This cannot bring back the freeze fixed in 5.0.1. Waiting longer only makes a
  frame *more* likely to be decoded, and with one frame queued the extra 12 ms
  delays nothing behind it.

  Same reasoning already applied to keyframes at 100 ms in 5.0.0, which is why
  that same episode lost **zero** keyframes. This extends it to ordinary frames
  at a proportionate number.

### Note

- **How to tell whether this worked:** the debug overlay's `DEC dropped N` should
  fall well below the 71-per-episode baseline. If it does not, the remaining
  drops are longer stalls than 16 ms covers, and the next question is what is
  holding the codec up rather than how long to wait for it.

---

## [5.0.2] - 2026-08-03

### Fixed

- **The debug overlay did nothing when switched on.** `StreamStats.overlayEnabled`
  was written in exactly one place — `FerryService`, when the receiver starts —
  and the Settings toggle only persisted the value. So turning the overlay on had
  no effect until the service happened to restart, which is indistinguishable
  from the feature being broken. The toggle now pushes the flag straight to
  `StreamStats`, the same way smart fill three lines below it always has.

- **The overlay sat inside the TV overscan region.** Its margin was 48 *pixels* —
  about 2.5% of a 1080p edge, where a television is free to crop 5%. On a set
  that overscans it was rendered off-screen. Now inset to the documented Android
  TV safe area (48dp horizontal, 27dp vertical), which is roughly double the old
  margin at xhdpi.

  Worth recording, since it was the first suspect: this had nothing to do with
  smart fill. `applyAspectFit` only ever resizes the `SurfaceView`, so cropping
  the video never touched the overlay.

---

## [5.0.1] - 2026-08-03

### Fixed

- **5.0.0 could freeze the picture until the session was restarted. Regression,
  introduced by 5.0.0, fixed here.**

  5.0.0 started arming a keyframe resync whenever the decoder dropped a frame
  that later frames predict from. The reasoning was right — that frame's absence
  does break prediction — and the result was much worse than the problem.

  Arming a resync stops *every* frame reaching the decoder until an IDR arrives,
  and on iOS that is around ten seconds away. The mistake was assuming these
  drops are rare. They are not: unlike a queue overflow, a decoder-level drop
  happens whenever no input buffer frees up in time, which on a decoder that is
  merely keeping up is often. So each drop bought a ten-second freeze, and the
  next drop landed moments after recovery. The screen went from occasionally
  showing artifacts to showing roughly one frame every ten seconds — which reads,
  correctly, as the TV having locked up, with nothing but stopping and restarting
  the cast to clear it.

  A decoder-level drop no longer arms a resync. It is still counted, and the
  keyframe input-buffer priority added in 5.0.0 still stands — that is the half
  of the change that attacks the cause instead of the symptom, by making the loss
  of an actual IDR far less likely.

- **Waiting for a keyframe is now bounded, so it can never be an indefinite
  freeze again.** Skipping frames until an IDR had no upper bound at all. If the
  IDR never came — a sender deferring keyframes on a static screen, or anything
  re-arming the resync as fast as it cleared — the picture simply stopped, for
  good. It now gives up after 3 seconds and lets frames through, accepting brief
  artifacts until the next IDR rather than a still screen with no way back.

  This is a structural guarantee rather than a fix for one cause: whatever arms a
  resync, the picture starts moving again within 3 seconds.

### Note

- The trade this release settles: **a moving picture with transient artifacts
  beats a still one.** Corruption from a lost reference frame self-heals at the
  sender's next IDR either way. The only thing in question is whether the
  intervening seconds show something or nothing, and 5.0.0 answered that wrong.

---

## [5.0.0] - 2026-08-03

### Fixed

- **The picture no longer morphs for 10-15 seconds at a time.**

  The symptom: mid-session, pixels smear or shift colour and stay wrong for
  roughly 10-15 seconds before healing on their own — sometimes longer, and
  rotating the sending device fixes it immediately.

  That duration was the clue. It is not a glitch, it is a GOP. A 300-frame
  keyframe interval at 30 fps is exactly 10 seconds. The picture was staying
  broken until the sender's next IDR, which is what you would expect if a
  reference frame went missing and the decoder kept decoding frames that predict
  from it. Ferry cannot ask for a keyframe — the mirroring path it implements has
  no back-channel for that — so it had to wait for the encoder's own.

  Three separate faults, all of which had to be fixed for it to stop:

  **Frames were being dropped in a second place nobody was watching.**
  `MirrorStreamServer.enqueue` has a careful drop policy: when it sheds a frame
  that later frames reference, it arms a keyframe resync so the decoder stops
  being fed predicted frames it cannot resolve. But `VideoDecoder.decodeNalUnit`
  drops frames too, when no MediaCodec input buffer comes free in time, and it
  did so silently — a verbose log line and nothing else. That drop never reached
  the resync logic, so the stream went reference-broken with nothing recording
  it. `decodeNalUnit` now reports whether the codec accepted the frame, and a
  dropped frame that anything predicts from arms the resync like any other.

  **The resync flag was cleared before the keyframe was actually decoded.**
  The old order was: see that we are resyncing, clear the flag, then hand the
  frame to the codec. If the codec then dropped that very IDR, the resync was
  recorded as finished while no keyframe had reached the decoder — and since the
  flag was already clear, nothing re-armed it. Every predicted frame afterwards
  decoded against nothing. The flag is now cleared only once the codec has taken
  the frame.

  **A keyframe was given the same 4 ms to find an input buffer as any other
  frame.** Those two cases are not comparable. Losing a predicted frame costs one
  frame; losing an IDR costs every frame until the next one, a whole GOP later.
  Keyframes now wait up to 100 ms — about six frame intervals, into a queue that
  holds sixteen and sheds non-reference frames first. Tens of milliseconds
  against tens of seconds.

  There is also a concurrency consequence of that wait. The decoder thread can
  now block inside a single decode call while the reader thread keeps running, so
  the reader can arm a *fresh* resync for a gap that lands after the in-flight
  keyframe. Clearing the flag on that keyframe's success would have silently
  reintroduced the same corruption. A sequence counter makes the clear conditional
  on the resync being the one that just completed.

### Added

- **Decoder-level drops are now counted and visible** in the debug overlay and
  the periodic log — `DEC dropped N keyframes lost N`, separate from the queue's
  existing drop percentage. They previously existed only as a verbose log line,
  which is why this took a while to find: during an episode the queue drop rate
  stays flat, because the frames were being lost somewhere it was not counting.

### Note

- **Repeated SPS/PPS is logged but deliberately does not arm a resync.** Rotating
  the sender fixes a stuck picture because it changes the frame dimensions, so
  the parameter sets differ and the decoder is rebuilt; when they are identical
  the config is currently ignored. Treating any repeated config as a resync point
  would automate that recovery **if** this sender pairs parameter sets with IDRs,
  and would freeze a healthy stream for up to a full GOP if it does not. Which
  one iOS actually does cannot be determined from this side, so 5.0.0 logs the
  occurrences instead of guessing. The log settles it.

---

## [4.5.0] - 2026-08-03

### Security

- **The pairing PIN is now OFF by default.** Ferry ships open: any device that can
  reach the subnet can mirror to the TV without anyone touching it, and the only
  access control in that configuration is the network perimeter. 2.0.5 through
  4.0.0 defaulted it on; this reverses that.

  The trade being made is friction against exposure. Ferry is an appliance driven
  by a remote control, and a PIN on every connection is real cost on a network the
  owner already trusts. That is the common case and it now gets the default. It is
  the wrong default for a TV on guest, shared, or open Wi-Fi, and `SECURITY.md`
  and the README both say so rather than leaving it implied — the settings row
  says it too, so it is visible at the point of decision.

  **Upgrading does not silently disable a PIN you already set.** Settings persist
  as a whole record, so any user who has ever changed a setting has their choice
  stored and it wins. The new default reaches fresh installs and people who never
  opened Settings.

- **Cleartext HTTP is now declared instead of accidentally blocked.** At
  targetSdk 28+ Android blocks cleartext by default, and Ferry had no declaration
  at all — so it inherited the block, and two features that need it were failing
  silently: DACP reverse control (`runCatching { }.onFailure { Logger.e(...) }`,
  so a blocked request logged and did nothing) and AirPlay video URL playback,
  whose URLs are routinely `http://` on the LAN.

  This flag only ever governed the platform HTTP stack. Every AirPlay protocol
  port — RTSP 7000, mirror video 7100, audio 6001 — is a raw socket and was never
  affected, so the block was not protecting the bulk of the traffic; it was only
  breaking those two paths. Ferry has no hardcoded remote endpoint and contacts
  only the sender that connected to it. Now stated explicitly, with the reasoning,
  in `network_security_config.xml`.

- **Location permissions capped at API 32.** `ACCESS_FINE_LOCATION` and
  `ACCESS_COARSE_LOCATION` now carry `maxSdkVersion="32"`, and
  `NEARBY_WIFI_DEVICES` carries `neverForLocation`. Android 13 added the nearby-devices
  permission precisely so an app scanning for Wi-Fi Direct peers stops having to hold a
  location permission it does not use. Ferry never used it — there is no
  `LocationManager` anywhere in the tree — but on any modern device it was still asking,
  and the user had to take that on trust. The manifest now says it outright.

- **Config-frame parsing is bounded by the frame, not the buffer.** With the reader
  buffer now reused (below), an SPS/PPS length field that overruns the frame can still
  land inside the array — in bounds, no exception, decoder configured from whatever the
  previous frame left there. Every offset is now checked against the payload's real
  length, with tests for both the overrun and the reused-buffer case.

### Changed

- **The release APK is 53.9% smaller** — 11.6 MB → 5.6 MB.

  Two causes, both inherited. `keepDebugSymbols += "**/*.so"` in the packaging
  block applied to every variant, not just debug, so release APKs shipped full
  DWARF debug info: 81% of `libalac.so` was `.debug_*` sections, for a decoder
  whose actual `.text` is 138 KB. And the Fire TV build carried x86 and x86_64
  copies of both native libraries, which no Fire TV can execute — every Stick,
  Cube, and Omni panel Amazon has shipped is ARM. ABI sets are now per flavor;
  googletv keeps x86 for Intel boxes, ChromeOS, and emulators.

  This matters more than a download size: it installs onto a stick that typically
  has very little free storage.

- **The mirror reader reuses its payload buffer.** Each video frame allocated a
  fresh `ByteArray` to read into and a second one for the decrypted output. The
  read buffer is consumed synchronously — `cipher.update()` returns its own array,
  and config frames are copied out — so nothing retains it and it can be reused.
  Halves the large short-lived allocations on the 60 fps path, on a device with
  very little GC headroom. It grows on demand and stops retaining past 2 MB, so an
  outsized frame can't pin memory for the session.

### Fixed

- **The README described a project that no longer existed.** It still claimed
  "everything else is upstream's, deliberately left alone" and listed the feature
  set as "inherited from upstream, unchanged" — written at import, when both were
  true. Since then Ferry has changed ~2,000 lines across 42 files, 13 of them new,
  including most of the video pipeline. Rewritten against what the code now does,
  with a settings table that previously did not exist and an install command that
  named an APK the release workflow does not produce.

  The provenance section stays, shortened but not softened. It is a GPL
  obligation and it is true; `NOTICE` remains the authority.

---

## [4.0.0] - 2026-08-03

### Added

- **Always mirror the screen** (Settings → AirPlay; off by default).

  AirPlay has two modes, and until now which one you got was the sending app's
  decision, made per-app and with no visible logic to it. Play a plain remote
  stream and the app hands the URL to Ferry, which fetches and plays it itself —
  its own player on the TV, the iPad demoted to a remote. Play something with a
  custom renderer, overlays, or DRM it can't delegate, and you get the whole
  screen mirrored instead. Same TV, same app picker, two different experiences.

  This setting clears the video-URL capability bit Ferry advertises, so senders
  stop offering that route and every session arrives as mirroring.

  Off by default, because the video route is the better one where it's available:
  the TV pulls the stream at full source resolution rather than decoding a
  re-encode of somebody's screen, and the sender can be locked and pocketed. The
  setting trades that away for predictability, which is worth it only if the
  inconsistency is what's bothering you.

  Not offered in reverse: forcing *every* session into the video route would
  require the sending app to have a URL to hand over, and no receiver-side
  setting can conjure one.

### Changed

- **The `features` bitmask is defined once.** It was three hand-maintained copies
  of the same literal — the mDNS TXT record, `GET /info`, and `GET /server-info`
  — which is three chances to drift. A sender that discovers one capability set
  and is then told a different one mid-handshake fails somewhere well away from
  the cause. Now in `AirPlayFeatures`, with the wire format pinned by tests.

### Fixed

- **`TECHNICAL_SPEC.md` §8 documented the wrong bit numbers.** The feature-flag
  table — inherited verbatim from upstream, written in future tense before there
  was an implementation — put Screen at bit 5, Audio at 7 and AudioRedundant at
  9. The two public references both put them at 7, 9 and 11. It also disagreed
  with Ferry's own constant about bits 2 and 26.

  Rebuilt from the constant, with names checked against the
  [Unofficial AirPlay Specification](https://openairplay.github.io/airplay-spec/features.html)
  and [AirPlay 2 Internals](https://emanuelecozzi.net/docs/airplay2/features/),
  and the bits neither reference names now marked as undocumented rather than
  given plausible-looking labels. Nothing behavioral — the advertised mask is
  unchanged apart from the setting above — but §8 is the page you'd trust when
  touching this, and it was confidently wrong.

---

## [3.1.0] - 2026-08-02

### Added

- **Audio boost** (Settings → Audio boost; Off by default, then +3/+6/+9/+12 dB).
  For sources that are simply quiet — the TV is already turned up and it still
  isn't enough.

  It could not be built on `AudioTrack.setVolume`, which is capped at unity and
  can therefore only ever make things quieter. The gain comes from the platform's
  `LoudnessEnhancer` instead, which applies it down in the audio HAL and
  *compresses* rather than plainly scaling, so a boosted loud passage doesn't
  clip into distortion the way a naive multiply would. Both audio paths get it.

  Like smart fill, the setting applies to audio that is already playing rather
  than at the next session — you can only judge "is this loud enough now?" while
  listening to it.

### Fixed

- **The sender's volume slider was mapped linearly onto a decibel scale.** AirPlay
  reports volume in dB (−30 … 0); `AudioTrack.setVolume` takes a linear amplitude.
  Those were being treated as the same scale, so the conversion was correct only
  at the two endpoints:

  | sender slider | dB    | correct | was   | error    |
  |---------------|-------|---------|-------|----------|
  | max           | 0     | 1.000   | 1.000 | none     |
  | three-quarter | −7.5  | 0.422   | 0.750 | +5.0 dB  |
  | middle        | −15   | 0.178   | 0.500 | +9.0 dB  |
  | quarter       | −22.5 | 0.075   | 0.250 | +10.5 dB |
  | min           | −30   | 0.032   | 0.000 | −∞       |

  Audibly: a slider that did almost nothing across its top half and then collapsed
  near the bottom. Because both endpoints were already right, this is invisible if
  you only ever listen at full volume. Now `10^(dB/20)`, in the new `AudioGain`.

- **The legacy RAOP audio path ignored the sender's volume entirely.** The RTSP
  `volume` parameter was routed only to the mirroring audio server, so `AudioPlayer`
  — which is the path an audio-only session such as Apple Music actually runs on —
  had no volume handling at all, and the phone's slider did nothing. It now
  receives the same updates, and re-applies the gain if its `AudioTrack` is rebuilt
  (a volume can arrive before the track exists).

- **Reset to defaults** did not push the live-applied settings back, so a reset
  left a running session on the old smart-fill behaviour while the UI showed the
  new one.

---

## [3.0.0] - 2026-08-02

### Added

- **Smart fill** (Settings → Smart fill, on by default). Scales the mirrored
  picture to fill the TV, cropping up to 15% of it rather than showing black
  bars. Turn it off to go back to showing the whole picture with bars.

  A source's shape belongs to the sending device and cannot be negotiated: an
  iPad's screen genuinely is 4:3, and a 16:9 TV can only show it with bars, a
  crop, or distortion. Nothing the receiver advertises changes that — the
  `displays` record in `/info` influences what a *Mac* encodes, while iOS mirrors
  its own screen geometry regardless.

  So this trades one for the other, with a cap. A 4:3 iPad needs 25% of crop to
  fill completely; it takes the 15% it is allowed and keeps the rest as side
  bars, which are now roughly a quarter of their old width. On an iPad the
  cropped slice is the status bar and dock area rather than anything central.
  A landscape iPhone, already close to filling, loses very little. A 16:9 source
  is untouched in either mode, and portrait sources stay pillarboxed because the
  cap stops them being blown up.

  The toggle applies to the live picture within ~200 ms rather than at the next
  session, so its effect can be judged directly.

### Note

- Major version bump because this changes what the picture looks like by
  default, not because of an API break.

---

## [2.0.6] - 2026-08-02

### Changed

- Frame classification is now a **single pass**. Deciding whether a frame is
  disposable (for the drop policy) and whether it is a keyframe (for resync)
  reads the same 1-byte NAL headers, so `classify` answers both at once and the
  result rides on the queued frame — the decoder thread no longer re-walks a
  frame the reader thread already walked. Halves the work in the resync case,
  which is exactly when the pipeline is already struggling.

  The early exit on the first referenced slice is preserved, so the common case
  is not slower: a typical single-slice frame still settles after 4 bytes rather
  than a walk over ~100 KB of payload. Keyframe-ness is still correct at that
  exit because H.264 does not mix IDR and non-IDR slices within one access unit,
  and an IDR slice always carries `nal_ref_idc != 0`.

- The wait for the video Surface at session start now polls every 5 ms instead
  of every 100 ms. The 5 s ceiling is unchanged; what changed is measurement
  lag. The old interval added up to 100 ms of black screen *after* the Surface
  already existed, sitting directly between "sender connected" and "first frame
  drawn".

- Added a **baseline profile** plus `androidx.profileinstaller`, so ART compiles
  the startup path and the streaming hot path ahead of time instead of
  interpreting them and waiting for the JIT. On API 24-30 there is no
  Play-supplied cloud profile, and Fire OS 6 is API 25, so this is the only way
  the target device gets any AOT compilation at all.

  The profile is hand-authored: generating one properly needs the
  `androidx.baselineprofile` plugin driving a real device or emulator, and
  neither is available here. It should be regenerated from a recorded run if a
  device ever becomes reachable.

---

## [2.0.5] - 2026-08-02

### Security

- **PIN authentication is now ON by default.** Ferry listens on every interface
  and has no transport authentication of its own, so off-by-default meant any
  device that could reach the LAN — a guest, an untrusted IoT device on the same
  subnet — could put arbitrary video on the screen. Existing installs that have
  explicitly saved a value keep it; only fresh installs and untouched settings
  pick up the new default.

  Toggling the setting now also clears the pair-setup lockout counter. The
  lockout is deliberately persistent and normally only a *successful* pairing
  resets it, which is right against a guesser but leaves the owner stranded if
  pairing fails ten times for any other reason. Reaching the toggle needs
  physical access to the TV, which a remote attacker does not have.

- **Fixed a remote stack-overflow DoS in the RTSP reader.** `RtspRequestReader`
  recursed once per blank line before a request line; Kotlin does not
  tail-optimise that, so a peer sending a few thousand newlines exhausted the
  stack and killed the connection thread. Now an iterative skip with a hard cap.
  Reachable pre-pairing from any LAN device.

- **Fixed an unbounded FU-A reassembly buffer (remote OOM).** Nothing in the
  FU-A format obliges a sender to ever set the end bit, so a start fragment
  followed by endless middle fragments grew the accumulator until the heap died.
  Reassembly is now capped at 4 MB — far above any real keyframe — and an
  over-long reassembly is discarded, with the decoder resyncing at the next
  keyframe.

- **Request bodies are no longer eagerly decoded as UTF-8.** Every request body
  was converted to a `String` on parse, including photo PUTs of up to 25 MB of
  JPEG. That produced a multi-megabyte run of replacement characters that no
  handler ever read — wasted heap on a constrained device, and a lever a peer
  could pull deliberately. `RtspRequest.body` is now decoded lazily on first
  access; `bodyBytes` remains the authoritative wire form.

### Changed

- AVCC → Annex-B conversion for mirroring video is now done **in place**. An
  AVCC length prefix and an Annex-B start code are both exactly 4 bytes, so the
  conversion is a pure overwrite and needs no second buffer. This removes a
  `ByteArrayOutputStream` and its `toByteArray()` copy per frame — at 60 fps,
  two large short-lived allocations a frame on a device with very little GC
  headroom.

- Verbose and debug logging on per-frame, per-packet and per-byte paths no
  longer builds its message string in release builds. `Logger.v`/`Logger.d` gained
  inline lambda overloads that skip evaluation when no Timber tree is planted
  (release plants none). The worst case was `RtpInterleaved`'s resync loop, which
  allocated a string for every skipped *byte*.

---

## [2.0.4] - 2026-08-02

### Changed

- Mirroring video decode now runs MediaCodec in **asynchronous mode**. The
  synchronous design had two costs that were structural rather than incidental:

  - Output was drained only from inside `decodeNalUnit`, so a decoded frame sat
    undrained until the *next* frame arrived to push it out — one frame of
    latency built into the shape of the loop, independent of how fast the codec
    was.
  - Every frame whose input buffer was not immediately free stalled the decoder
    thread in `dequeueInputBuffer` for up to 12 ms, against a 16.7 ms budget at
    60 fps. The stall then backed arrivals up into the 16-deep frame queue, so
    the wait tended to cause the overflow it was meant to prevent.

  In async mode the codec hands us input buffers as they free up and renders
  each frame the moment it is decoded, on its own thread. Neither cost remains.

- The decoder now asks for realtime scheduling: `KEY_PRIORITY = 0` and
  `KEY_OPERATING_RATE = 60` (both API 23+, so they apply on Fire OS 6), plus
  `KEY_LOW_LATENCY` on API 30+, which disables output reordering. These trade
  power for latency, which is the correct trade for mirroring — there is no
  seek or scrub to amortise buffering against, and a frame decoded late is worth
  less than a frame decoded now.

### Note

- The **Higher resolution (1440p)** setting is best left **off**. It advertises a
  2560x1440 display to the sender, which is 1.78x the pixels of 1080p through a
  decoder that is already the bottleneck, and the panel downscales the result
  anyway. It also only affects macOS senders — iOS mirroring chooses its own
  geometry regardless.

---

## [2.0.3] - 2026-08-02

### Fixed

- Fire TV launcher banner, for real this time — confirmed against the device
  rather than against the documentation. Two independent requirements, each of
  which was learned by shipping the other one wrong:
  - the banner must live in a **density bucket**; Fire OS does not resolve a
    `drawable-nodpi` banner and silently falls back to the square launcher icon
    (this was the pre-1.1.1 behaviour, and 2.0.2 reintroduced it);
  - it must be **1280x720**, Fire OS's tile size, not the 320x180-at-xhdpi figure
    from the Android TV guidelines (1.1.1 through 2.0.1 shipped the small one,
    which resolved but sat small inside the tile).

  Now a single 1280x720 asset in `drawable-xhdpi`, the density Fire TV sticks
  report. Oversized in dp terms — the launcher scales it down to its slot — which
  is the right way round, since sharp-and-scaled beats correct-and-tiny.

---

## [2.0.2] - 2026-08-02

### Fixed

- Fire TV launcher banner rendered small inside its tile instead of filling it.
  1.1.1 split the banner into density buckets sized to the Android TV figure
  (320x180 at xhdpi); Fire OS asks for 1280x720, four times that linear size, so
  a Fire TV stick was handed a quarter-size image for a full-size tile. Back to a
  single 1280x720 `drawable-nodpi` asset — a fixed-size image the launcher scales
  into its own slot, which is what it was before 1.1.1 and what Fire OS expects.
  The part of 1.1.1 that mattered, `android:banner` on the leanback activity, is
  unchanged.

---

## [2.0.1] - 2026-08-02

### Added

- Build version shown on the home screen, under the device name (e.g.
  `v2.0.0-firetv`). Sideloading gives no install feedback, so this is the only
  way to confirm at a glance which build is actually on the device. The flavor
  suffix comes along from `versionNameSuffix`, which also disambiguates the
  firetv and googletv builds when both are installed.

---

## [2.0.0] - 2026-08-01

### Changed

**Mirroring latency and A/V sync**

Both media paths handed work between threads over a queue sized about a second
deep — video ~1.5 s, audio ~1.0 s. Against a live sender that is not headroom,
it is permanent latency: the sender produces at real time, so a queue that fills
during one hiccup never drains and the backlog persists for the rest of the
session. The differing depths, drifting independently, were also the A/V desync.

- Video decode queue 90 → 16 frames (~1.5 s → ~267 ms).
- Audio jitter buffer 96 → 32 frames (~1 s → ~350 ms). Kept deliberately deeper
  than video: an underrun is an audible crackle, a late video frame is invisible.
- Frame drops now prefer frames nothing references (`nal_ref_idc == 0`), falling
  back to the old drop-oldest-and-resync only when the sender marks everything as
  a reference. Overflow previously *always* forced a wait for the next IDR, and
  iOS emits those seconds apart — so a shallower queue without this would have
  traded steady lag for repeated multi-second freezes.
- `Video stats:` log line now reports `keyframeWaits` separately from total
  drops. The two have very different impact and only the former is visible.
- Decoder input-buffer timeout 100 ms → 12 ms. On the decoder thread a 100 ms
  stall is ~6 frames' worth of arrivals piling up behind it — the wait caused the
  overflow it was meant to prevent.
- Mirror data socket: `BufferedInputStream`, `TCP_NODELAY`, and a 1 MB receive
  buffer. Every frame previously cost a bare syscall for its 128-byte header.
- `MediaCodec.BufferInfo` hoisted out of `releaseOutputBuffers`, which runs twice
  per frame — ~120 short-lived allocations a second.
- `AudioPlayer` (RAOP path) sizes AudioTrack to the buffer floor rather than 2x
  it, and requests `PERFORMANCE_MODE_LOW_LATENCY` on API 26+.

### Fixed

- `TimingHandler.rtpClockOffsetUs` documented itself as feeding A/V correction in
  `AudioPlayer`. It never has — nothing outside its own tests reads it. Comment
  corrected to say so, and to record why a presentation clock is the wrong trade
  for mirroring: scheduling against one buys sync by *adding* latency.

---

## [1.1.1] - 2026-08-01

### Fixed

- Fire TV home row showed a stretched launcher icon instead of the banner.
  `android:banner` was declared only on `<application>`, but Fire OS reads
  `ActivityInfo.banner` for the leanback entry point and falls back to the square
  icon — not to the application banner — when the activity has none. Now declared
  on `MainActivity` as well.
- The banner shipped in `drawable-nodpi`, so launchers were handed the authored
  1280x720 image regardless of screen density and squashed it into a slot roughly
  a quarter that size. It now ships in real density buckets, `xhdpi` being the
  320x180 that the Android TV and Fire TV guidelines ask for.

### Changed

- README artwork moved to `docs/assets/` instead of pointing into
  `app/src/main/res/`. GitHub's raw CDN caches by URL, so reusing a resource path
  across a redesign keeps serving the superseded icon. `tools/generate-artwork.py`
  emits these alongside the launcher icons so the two cannot drift.

---

## [1.1.0] - 2026-08-01

### Changed

**UI — full visual revamp**
- Replaced the Google-TV-derived look (true black, Google blue `#1A73E8`, rounded
  cards) with a brutalist mono design: near-black ground `#0A0A0A`, a single
  terminal-green accent `#00E5A0`, monospace type throughout, hard edges and 1dp
  hairline borders. No corner radii, elevation, or shadows anywhere.
- Focus is now shown by **inversion** — a focused cell fills solid accent and its
  text flips to near-black — rather than by a 3dp focus ring. Reads far better
  across a room than a thin outline.
- Focus contrast is handled by `res/color/cell_text_*.xml` ColorStateLists plus
  `duplicateParentState`, so a new cell gets correct contrast without wiring up a
  focus listener.
- Home: header readout, section rules instead of floating cards, protocol cells
  butted into a continuous grid, outlined control buttons.
- Waiting screen: left-aligned terminal-style block, device name in the accent as
  the single hot element. Outer padding raised 48dp → 64dp to clear TV overscan.
- Settings: switches replaced by `[ ON ]` / `[ OFF ]` mono readouts — easier to
  read at distance than a small track. "Reset to defaults" inverts to red rather
  than to the accent, so a destructive row never looks like an approval.
- Nav rail: wordmark, accent rule, and a leading accent bar marking the current
  page when focus has moved into content.
- Pairing PIN is now monospace and accent-coloured — a code read off a TV and
  typed on another device should not have ambiguous `1`/`l` or `0`/`O`.

### Fixed

- Protocol status indicators tinted via `backgroundTintList` instead of
  `background.setTint()`. The old call mutated the drawable's shared
  `ConstantState`, which let one protocol cell's status colour leak into the
  other two.

---

## [1.0.0-beta.1] - 2026-06-14

### Added

**AirPlay 2 receiver — full stack**
- Screen mirroring (H.264) from macOS 12+ and iOS/iPadOS 16+ via RTSP on port 7000
- FairPlay session decryption: fp-setup v2 (RAOP audio) and v3 (mirroring/Safari) via native libplayfair (JNI); legacy rsaaeskey RSA-OAEP recovery for AirPort Express compatibility
- HomeKit-style pairing: Ed25519 identity, X25519 ECDH key agreement, controller key persistence (`PairingStore`), failed-attempt lockout
- Legacy SRP-6a PIN pairing with on-screen PIN entry screen (`LegacyPairSetupPin`, `PinScreen`)
- `MirrorStreamServer` + `MirrorCrypto` — interleaved RTP reassembly, AES-128-CTR stream decryption (keystream always advanced to prevent reuse)
- `AudioStreamServer` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC decode via MediaCodec, RAOP retransmit, AudioTrack with volume
- `AlacDecoder` + native libalac — RAOP/SDP audio path: AES-128-CBC (per-packet IV) + Apple's ALAC decoder; decode-health mute guard (wrong key → silence, not static)
- `BufferedAudioServer` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- `AirPlayVideoPlayer` — AirPlay video URL mode (`/play`) + transport controls (play/pause/scrub/stop)
- `NowPlayingInfo` (DMAP parser) + album artwork → `NowPlayingScreen` overlay
- `DacpClient` — `_dacp._tcp` discovery + reverse transport control from TV remote to sender (play/pause/skip/volume)
- `AirPlayNtpClient` — Apple NTP for A/V synchronisation
- `InfoResponder` — `GET /info` capability advertisement (plist)
- `PlistCodec` — Apple binary plist encode/decode
- `RaopRsa` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- `StreamStats` — per-session RTP statistics (packet count, duplicates, queue drops)
- `Base64Util` — pure-JVM Base64 so SDP parsing is testable without Android framework
- `SdpParser` — extended: codec/encryption/channel/rate parsing for all AirPlay audio types
- Aspect-fit (letterbox/pillarbox) video rendering with black background in `StreamingScreen`
- Real PNG bitmap launcher icon and TV banner (replaces placeholder XML)
- Mirror Audio toggle and PIN-auth toggle in Settings
- Receiver survives app restart/relaunch; mirroring and audio stop cleanly on app exit

**Native layer**
- CMake build for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- `fairplay_jni.c` — JNI bridge for `playfair_decrypt` with full null/length/OOM validation
- Apple ALAC decoder (C++, vendored) + JNI bridge (`alac_jni.cpp`)
- Reverse-engineered FairPlay (C, `playfair/`) compiled for all ABIs
- Strict-aliasing fix in `modified_md5.c` (union type-punning) and `sap_hash.c` (memcpy + union)

**Test suite**
- 247 unit tests, 0 failures: FairPlay, RaopRsa, Base64Util, ALAC cookie, DMAP, legacy PIN SRP, audio stream server, RTSP handler, service controller
- Robolectric added for framework-dependent tests (Android Base64, Intent, etc.)

**Release infrastructure**
- `scripts/release.sh` — local release script: builds signed GoogleTV + FireTV APKs, creates git tag, publishes GitHub Release via `gh` CLI (no CI minutes consumed)
- First signed GitHub Release: [v1.0.0-beta.1](https://github.com/mazer666/Ferry/releases/tag/v1.0.0-beta.1)

### Changed
- `VideoDecoder`: SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), re-attach to Surface after backgrounding
- `AudioPlayer`: extended to support ALAC and new audio stream types from `AudioStreamServer`
- `RtspHandler`: extended to 700+ lines — handles all AirPlay 2 verbs (ANNOUNCE, SETUP plist+SDP, RECORD, TEARDOWN stream-scoped, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio control)
- `AirPlayReceiver`: event channel socket now closed via `use {}` block (fixes file-descriptor leak)
- `SettingsFragment`: mirror audio and PIN-auth toggles added

### Fixed
- `DatagramPacket` length reset before each `receive()` call in `AudioStreamServer` — prevented packet truncation when a smaller packet arrived first
- JNI bridge (`fairplay_jni.c`) now validates input arrays for null, length, and OOM before native access — prevents out-of-bounds reads and native crashes
- Strict-aliasing UB in `modified_md5.c` and `sap_hash.c` — union + memcpy replaces direct `uint32_t*` cast of `unsigned char*`
- `Cipher.getInstance()` moved out of hot path in `AudioStreamServer` (~92 allocations/s → 1 per session)

---

<!-- Format:
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
-->
