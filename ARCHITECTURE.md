# Ferry — architecture

Aimed at someone who knows Kotlin and Android but has not worked with AirPlay, JNI,
or Android TV. Accurate over exhaustive: it covers the spine of the system and the
things that are non-obvious. Protocol and JNI specifics are explained; general Android
is not.

> Upstream PhairPlay's older, longer architecture notes are still in
> [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). This file is the current one.

---

## The shape of it

```
  iPhone / Mac                        Fire TV (Ferry)
  ────────────                        ───────────────
                    mDNS: _airplay._tcp / _raop._tcp
     discovery  <───────────────────  MdnsService          (port 7000 advertised)
                    RTSP/HTTP :7000
     handshake  ───────────────────>  RtspHandler
                                        ├─ pair-setup / pair-verify   PairingSession
                                        ├─ fp-setup                   FairPlay ──JNI──> libplayfair.so
                                        └─ SETUP/RECORD               SdpParser
                    H.264 over TCP :7100 (mirror) or RTP/UDP
     video      ───────────────────>  MirrorStreamServer ─> VideoDecoder ─> MediaCodec ─> Surface
                    ALAC/AAC over UDP :6001
     audio      ───────────────────>  AudioPlayer ─(ALAC)─JNI─> libalac.so ─> AudioTrack
                    NTP-ish timing :6002
     clock      <──────────────────>  TimingHandler
                    DACP (reverse control, sender's own port)
     remote     <───────────────────  DacpClient
```

Everything above lives inside one foreground service. The Activity is only a window
with a `SurfaceView` in it.

---

## Session lifecycle, step by step

**1. mDNS advertisement** (`airplay/MdnsService.kt`)

Ferry registers *two* Bonjour services via Android's `NsdManager`, both pointing at
TCP port **7000**:

- `_airplay._tcp` — the mirroring/video service. TXT records advertise `deviceid`
  (the MAC), `features` (a bitmask telling the sender what's supported — mirroring,
  video, audio), and `model`.
- `_raop._tcp` — "Remote Audio Output Protocol", the older AirPlay audio service.
  **Both are required even for pure screen mirroring**; iOS won't offer the device as
  a mirroring target if only `_airplay._tcp` is present. Its TXT records describe
  encryption types (`et`), metadata types (`md`), and transport (`tp=UDP`).

This is why `CHANGE_WIFI_MULTICAST_STATE` is in the manifest — without joining the
multicast group, mDNS is silently invisible.

After any session ends, `MdnsService.restart()` re-registers so the device reappears
in sender pickers immediately rather than after a timeout.

**2. RTSP handshake** (`airplay/RtspHandler.kt`, ~1000 lines — the protocol core)

One TCP listener on 7000, **single client at a time** (a second connection gets a 503).
The wire format is RTSP, but AirPlay 2 layers HTTP-style `GET`/`POST` requests with
binary-plist bodies over the same socket, so the request router dispatches on both:

| Verb | Purpose |
|---|---|
| `OPTIONS` | capability probe |
| `POST /pair-setup`, `/pair-verify` | AirPlay 2 pairing (Ed25519 / SRP) |
| `POST /fp-setup` | FairPlay — see below |
| `GET /info` | returns a binary plist describing the device |
| `ANNOUNCE` | SDP session description (legacy audio path) |
| `SETUP` | **plist body → AirPlay 2 mirroring; text body → legacy audio.** The branch that decides which kind of session this is |
| `RECORD` | start streaming — the media pipeline is created lazily here |
| `TEARDOWN` | end a stream, or the whole session |
| `PUT`/`DELETE /photo` | AirPlay photo display |
| `POST /play`, `/rate`, `/scrub`, `/stop` | AirPlay *video URL* mode (YouTube-style handoff, not mirroring) |

**3. Pairing** (`handshake/PairingSession.kt`, `PairingStore.kt`, `LegacyPairSetupPin.kt`)

Ed25519-based pair-setup/pair-verify. Paired controllers' public keys persist via
`PairingStore` so a returning sender skips the PIN. If PIN auth is enabled, a code is
displayed on the TV and verified over SRP-6a (this is why BouncyCastle is a
dependency — Android's `javax.crypto` has no SRP).

**4. `fp-setup` — FairPlay** (`handshake/FairPlay.kt` + `cpp/fairplay_jni.c`)

This is the part worth explaining, because it's unlike normal crypto code.

FairPlay is Apple's DRM handshake. Every AirPlay sender demands it before it will hand
over the key that decrypts the actual A/V stream. It is not documented, and there is no
specification — the implementations that exist were reverse-engineered.

It runs in two phases over `POST /fp-setup`:

- **Phase 1**: sender sends 16 bytes; receiver replies with a fixed 142-byte blob.
  There is no computation here at all — the replies are *captured constants*. Which
  constant to send depends on a version byte (`req[4]`) and a mode byte (`req[14]`):
  version `0x03` is mirroring/Safari (four mode-specific replies), version `0x02` is
  the RAOP audio path Apple Music uses (one reply, with the mode byte patched in at
  offset 13). Getting this branch wrong yields a wrong key for *every* mode, which is
  why the code is careful about it.
- **Phase 2**: sender sends 164 bytes (the "key message"); receiver echoes a 12-byte
  header plus bytes `[144,164)` back. The sender then uses that to wrap the real AES
  stream key, which arrives as `fpaeskey` — 72 bytes of FairPlay-wrapped ciphertext.

Unwrapping that 72-byte key is the only part that's real computation, and it's the
only part that crosses into native code:

```
FairPlay.decrypt(keyMessage[164], ekey[72])
   └─ JNI → Java_..._nativePlayfairDecrypt   (cpp/fairplay_jni.c)
        └─ playfair_decrypt()                (cpp/playfair/playfair.c)
             ├─ generate_session_key()       (omg_hax.c + omg_hax.h — 483 KB of tables)
             ├─ generate_key_schedule()
             ├─ cycle() / z_xor() / x_xor()  (hand_garble.c)
             └─ → 16-byte AES key
```

**Why native at all?** The algorithm carries ~483 KB of hardcoded lookup tables and
deliberately obfuscated bit manipulation (hence the names `omg_hax`, `hand_garble`).
Hand-porting it to Kotlin would be a large, error-prone rewrite of code that already
works. It's imported from RPiPlay and compiled as-is.

**The JNI boundary in practice.** There are exactly two native libraries, both built
from source in this repo by `cpp/CMakeLists.txt`, both loaded by a plain
`System.loadLibrary` (there is no other native loading anywhere — see `AUDIT.md`):

| Library | Bridge | Loaded by | Purpose |
|---|---|---|---|
| `libplayfair.so` | `fairplay_jni.c` | `handshake/FairPlay.kt:78` | FairPlay key unwrap |
| `libalac.so` | `alac_jni.cpp` | `handshake/AlacDecoder.kt:68` | ALAC → PCM |

Both are built for `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

**Both bridges validate lengths before touching native memory** — this matters because
every byte crossing here came off the LAN from an unauthenticated peer. `fairplay_jni.c`
rejects a key message under 164 bytes or a cipher under 72 (`playfair_decrypt` reads
fixed offsets up to `cipherText[71]`, so a short array would be an out-of-bounds read).
`alac_jni.cpp` checks the claimed input length against the real array length, checks the
output array is big enough for `frameLength × channels × 2` before decoding, and refuses
any magic cookie with `bitDepth != 16`. If you change anything in these files, preserve
those checks — they are the boundary between "malformed packet" and "heap corruption".

**5. H.264 arrival → MediaCodec → Surface**
(`handshake/MirrorStreamServer.kt` → `airplay/VideoDecoder.kt`)

Mirroring video arrives on its own TCP connection, AES-encrypted with the key recovered
above (`MirrorCrypto`). Frames are length-prefixed; SPS/PPS parameter sets arrive in
their own packet type and must reach `MediaCodec` before any frame does — `VideoDecoder`
parses the SPS to get real dimensions (`VideoDecoderSpsTest` covers this).

Decode is hardware: `MediaCodec` configured with the `Surface` from `StreamingScreen`'s
`SurfaceView`, so decoded frames go straight to the display with no bitmap copy. The
`Surface` is obtained lazily through a provider lambda, because the service owns the
pipeline but the Activity owns the window:

```
MainActivity.getVideoSurface()  ──setVideoSurfaceProvider──>  PhairPlayService
                                                                    │
                                                    videoSurfaceProvider?.invoke()
                                                                    ▼
                                                             AirPlayReceiver → VideoDecoder
```

The indirection matters: the Activity can be destroyed and recreated (or the Surface
destroyed on screen-off) while the service and the RTSP session live on. The provider
returns `null` when no Surface is available, and the pipeline tolerates that.

Audio takes a parallel path: AES-128-CTR decrypt → AAC (hardware) or ALAC (software,
via `libalac.so`, because TVs generally have no ALAC decoder) → `AudioTrack`.

**6. Session end**

Two ways, and both converge:

- **Clean**: sender sends `TEARDOWN`. AirPlay 2 can tear down *individual* streams
  (type 96 = audio, 110 = video) without ending the session, so `handleTeardownInternal`
  only ends the session when the last stream is removed.
- **Abrupt** (sender walks out of Wi-Fi range): the socket read throws. The client
  handler's `finally` block runs regardless — closing the socket, clearing session and
  pairing state, and calling `onStreamingStopped()`.

Both paths land in `AirPlayReceiver.onStreamingStopped()` → release media components →
`emitState(ProtocolState.ADVERTISING)` → restart mDNS.

**This is the single most useful fact in this document for anyone changing behavior on
session end:** there is no "sender disappeared" special case to write, because the
`finally` block already funnels abrupt drops into the same state transition as clean
ones.

---

## Where the foreground service sits

`service/PhairPlayService.kt` is a bound + started foreground service
(`foregroundServiceType="connectedDevice"`). It owns all three receivers
(AirPlay, Miracast, Cast) and every piece of session state. It is `START_STICKY`, but
`onTaskRemoved` deliberately stops it so swiping the app away doesn't leave a zombie
advertising on the network.

`MainActivity` binds to it, supplies the Surface provider, and observes its `StateFlow`s.
The Activity holds **no** session state of its own — it mirrors the service's.

`ServiceController` is the only way to send it `START` / `STOP` / `RESTART`.

Note `startAirPlay()` is idempotent: a redundant `ACTION_START` (e.g. Activity recreated
while the service is alive) returns early rather than starting a second receiver that
would fight for port 7000.

---

## Every place that knows whether a session is active

This is the catalogue the screensaver fix is built on. **Authoritative state lives in
exactly one place — `PhairPlayService` — and everything else is a mirror of it.**

### Source of truth: `PhairPlayService` StateFlows

| Flow | "Active" when | Set by |
|---|---|---|
| `airPlayState: StateFlow<ProtocolState>` | `== CONNECTED` | `AirPlayReceiver.onStateChanged` |
| `nowPlaying: StateFlow<NowPlayingInfo?>` | `!= null` — audio-only session (no video) | `onNowPlayingChanged` |
| `photoFrame: StateFlow<PhotoFrame?>` | `!= null` — AirPlay photo on screen | `onPhotoReceived` / `onPhotoCleared` |
| `pairingPin: StateFlow<String?>` | `!= null` — PIN displayed, session pending | `onPinChanged` |
| `activeConnection: StateFlow<ActiveConnection?>` | `!= null` | derived; AirPlay only, carries sender name + start time |
| `miracastState`, `castState` | `== CONNECTED` | the other two receivers |

### Producers (upstream of the above)

- `AirPlayReceiver.emitState(...)` — the **only** emitter of AirPlay `ProtocolState`.
  Marshals to the Main thread. Emits `CONNECTED` at three points (mirror start, buffered
  audio start, URL video start) and `ADVERTISING` from `onStreamingStopped()`.
- `RtspHandler` — `activeStreamTypes`, `currentSession`, `isMirrorSession` track
  per-stream liveness within a session; the `finally` block on client disconnect is the
  abrupt-drop funnel described above.

### Consumers

- **`MainActivity.updateOverlay()`** — the key one. It already computes exactly which
  overlay should be visible from all four "is something happening" flows, and its `else`
  branch is precisely the case "nothing is active":

  ```kotlin
  when {
      pin != null                                     -> showPinScreen(pin)
      nowPlaying != null                              -> showNowPlayingScreen(nowPlaying)
      currentAirPlayState == ProtocolState.CONNECTED  -> showStreamingScreen()
      photoFrame != null                              -> showPhotoScreen(photoFrame)
      else                                            -> hideStreamingScreen()
  }
  ```

  Every session transition — clean end, abrupt drop, photo cleared, PIN dismissed —
  already flows through this one function. **That makes it the correct and only place to
  drive the keep-awake flag**, and it's why the fix needs no new notion of "is something
  playing."

- `MainActivity.onKeyDown` — gates DACP remote commands on `currentNowPlaying != null ||
  currentAirPlayState == CONNECTED`.
- `HomeFragment` — status cards, per protocol.
- `PhairPlayService.updateNotification(...)` — notification text.

---

## Screensaver / keep-awake

`MainActivity.updateOverlay()` sets `window.keepScreenOn` from the same condition that
decides whether any overlay is visible. Active session → display stays on; idle → normal
screensaver and sleep behavior resume.

Deliberately a **window flag, not a wake lock**: it is scoped to the window lifecycle, so
it cannot leak if the app crashes or is killed, and it needs no `WAKE_LOCK` permission.
See `README.md` for the Fire OS caveat and `PROGRESS.md` for the full rationale.

---

## Testing

`test-runner/` is a plain **JVM** module that compiles the protocol sources against
stubs (`src/stubs/`) so parser and handshake logic can be tested without an emulator —
`./gradlew :test-runner:test` is fast and is what CI runs first. `app/src/test/` holds
the Robolectric-backed Android-dependent tests.
