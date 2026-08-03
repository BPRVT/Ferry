# Changelog

All notable changes to Ferry will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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

- **`TECHNICAL_SPEC.md` §8 described a bitmask Ferry doesn't advertise.** The bit
  table claimed VideoFairPlay (bit 2) was unset and AirPlayVideoV2 (bit 26) was
  set; the actual value has it exactly the other way round. Corrected against
  the constant.

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
