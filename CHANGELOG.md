# Changelog

All notable changes to Ferry will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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
