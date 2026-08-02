<img src="docs/assets/ferry-icon.png" alt="Ferry icon" width="64">

# Ferry

**A screen-mirroring receiver for Amazon Fire TV and Android TV.** Compatible with the
AirPlay 2 protocol, so an iPhone, iPad, or Mac can mirror to a TV stick that has no
native support for it. Everything happens on your local network — Ferry needs no
internet connection and no account.

A ferry carries something across to the other side. That's the job.

```
 iPhone / iPad / Mac                Fire TV / Android TV
 ┌────────────────┐    local Wi-Fi   ┌──────────────────────┐
 │  [Your Screen] │ ───────────────► │   [Your TV Screen]   │
 └────────────────┘                  └──────────────────────┘
   Screen Mirroring →                        Ferry
   Select "Ferry" →
   Done.
```

---

## This is a derivative work — read this first

**Ferry is derived from [mazer666/PhairPlay](https://github.com/mazer666/PhairPlay).**
It is a standalone repository rather than a GitHub fork, but it is not an original
implementation and does not pretend to be. The AirPlay protocol stack — RTSP handling,
the AirPlay 2 handshake, mDNS advertisement, the MediaCodec pipeline — is PhairPlay's
work. PhairPlay's own AirPlay implementation traces back through
[UxPlay](https://github.com/FDH2/UxPlay) to [RPiPlay](https://github.com/FD-/RPiPlay),
the FairPlay code originates in
[EstebanKubata/playfair](https://github.com/EstebanKubata/playfair), and the protocol is
documented by [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec).
The ALAC decoder is Apple's own open-source code.

The full chain, with every attribution obligation spelled out, is in [`NOTICE`](NOTICE).

### What Ferry changes

| Change | Detail |
|---|---|
| **Screensaver fix** | The Fire TV screensaver no longer interrupts an active mirroring session. This is why the project exists. [Details below.](#the-screensaver-fix) |
| **Licensing corrected** | GPLv3 instead of Apache 2.0, to match the actual provenance of the native code. Missing license headers added to `cpp/playfair/`. [Details below.](#licensing--gplv3-and-why-it-changed) |
| **Security audit** | The whole baseline was audited before anything was changed. [`AUDIT.md`](AUDIT.md) |
| **Rebrand** | New name, package `com.ferry.receiver`, icon, banner, advertised device name. |
| **Release provenance** | Tagged releases build the APK from source in CI — upstream had no release workflow. |

Everything else is upstream's, deliberately left alone.

### Licensing — GPLv3, and why it changed

Upstream declared Apache 2.0. That declaration did not cover the code actually being
distributed.

`app/src/main/cpp/playfair/` is the FairPlay key-decryption implementation from
EstebanKubata/playfair, redistributed through RPiPlay. **Both of those projects are
GPLv3.** GPLv3 code cannot be relicensed under Apache 2.0; the reverse direction is
fine. So GPLv3 is the only license the combined work can validly carry, and Ferry is
GPLv3.

Apple's ALAC decoder in `cpp/alac/` remains under Apache 2.0 and **keeps its copyright
headers verbatim** — the two licenses combine fine in one binary, with the
Apache-licensed files retaining their own notices. No upstream copyright header has been
removed or altered anywhere in this repository.

One point of fact, since it is easy to assume otherwise: **upstream did not strip those
license headers.** RPiPlay's own copies carry no per-file header either; RPiPlay conveys
its license at the project level. The omission was inherited, not introduced. Ferry adds
the headers to stop it propagating further.

This is a good-faith reading, not legal advice. See [`NOTICE`](NOTICE) for the full
reasoning — and talk to a lawyer if you plan to redistribute commercially.

---

## Install

Prebuilt APKs are on the [Releases](../../releases) page, built from a tagged commit by
CI. Building it yourself is strictly better, and not hard — see below.

On your Fire TV: **Settings → My Fire TV → Developer Options** → enable *ADB debugging*
and *Apps from Unknown Sources*. Find the IP under **Settings → My Fire TV → About →
Network**.

```bash
adb connect 192.168.1.42:5555
adb install -r app-firetv-release.apk
```

Accept the authorization prompt on the TV the first time. Then launch **Ferry** from the
Fire TV home screen.

**To mirror:** on iPhone/iPad, Control Center → **Screen Mirroring** → *Ferry*. On a Mac,
the AirPlay/Screen Mirroring icon in the menu bar or Control Center → *Ferry*.

Ferry must be running, and the sender must be on the same network.

To advertise a different name, set **Settings → Device Name** in the app.

---

## Build from source

Requires **JDK 17**, Android SDK **platform 35** and **build-tools 35.0.0**, **NDK
28.2.13676358**, and **CMake 3.22.1**. The NDK and CMake versions are pinned in
`app/build.gradle.kts`; the Gradle wrapper fetches Gradle itself.

```bash
git clone https://github.com/BPRVT/Ferry.git
cd Ferry
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleFiretvDebug
```

The APK lands in `app/build/outputs/apk/firetv/debug/`.

```bash
./gradlew :test-runner:test        # fast JVM protocol tests — no emulator needed
./gradlew :app:lintFiretvDebug     # lint (warnings are errors)
```

Two product flavors: `firetv` (minSdk 25, application ID `com.ferry.receiver`) and
`googletv` (minSdk 29). The Google Cast receiver SDK is scoped to `googletv` only —
**no Google Play Services code ships in the Fire TV APK**, which is verified rather than
assumed (see the audit summary).

Placeholder artwork is generated by `tools/generate-artwork.py`; replace the PNGs under
`app/src/main/res/` with your own whenever you like.

---

## The screensaver fix

**The bug:** during active mirroring, the Fire TV screensaver would activate and
interrupt playback.

**The cause:** nothing in the codebase ever asked for the screen to stay on. A repo-wide
search for `FLAG_KEEP_SCREEN_ON`, `keepScreenOn`, `WakeLock`, and `PowerManager` returned
zero hits, and `WAKE_LOCK` was not even declared in the manifest. Mirroring renders
decoded frames straight to a `SurfaceView` with no user input, so as far as the OS is
concerned the device is idle and the normal idle timer runs to completion.

**The fix:** `MainActivity` sets `FLAG_KEEP_SCREEN_ON` while a session is active and
clears it when one is not.

A window flag, not a wake lock — deliberately. It is scoped to the window, so the system
drops it automatically if the app is killed or crashes. A leaked
`SCREEN_BRIGHT_WAKE_LOCK` would pin the TV awake until reboot, which is a worse bug than
the one being fixed. It also needs no `WAKE_LOCK` permission, so Ferry's permission set
is unchanged.

**Scoped to real sessions.** The flag is driven off existing session state through a
single shared predicate (`isSessionActive`) that also decides which overlay is on screen,
so the two cannot drift apart. It covers mirroring, audio-only playback, a displayed
photo, and a pairing PIN. **When Ferry sits idle waiting for a connection, normal
screensaver and sleep behavior resume.**

Abrupt disconnects — the sender walking out of Wi-Fi range — are handled: the RTSP
handler's `finally` block runs on the socket exception and funnels into the same state
transition as a clean `TEARDOWN`.

### Fire OS caveat — please verify this on your device

**Whether the standard Android flag is sufficient on Fire OS specifically cannot be
determined from source, and I'm not going to pretend otherwise.**

`FLAG_KEEP_SCREEN_ON` is the correct, documented Android mechanism and prevents the
display timing out on stock Android TV. Fire OS is a fork, and its screensaver is a
system-level feature with its own settings entry (**Settings → Display & Sounds →
Screensaver**). Whether that timer always defers to an app holding the flag is a
device-behavior question that varies across Fire OS versions and can only be answered on
the hardware.

So test it. Start a mirroring session and leave it past your screensaver timeout.

- **Nothing happens** → the flag works on your device. Done.
- **The screensaver still appears** → the OS is overriding the flag. Set **Settings →
  Display & Sounds → Screensaver → Start after → Never** as a workaround, and please open
  an issue with your Fire OS version.

Ferry deliberately does **not** hack around a system-level override by faking input
events or holding a screen-bright wake lock. Those approaches are fragile and risk
leaving the TV awake permanently — the exact failure mode being avoided.

### Manual test plan

1. Start mirroring from an iPhone or Mac.
2. Leave it past your screensaver timeout — set the timeout to 2 minutes to make this
   quick. **Expected: nothing happens, mirroring continues.**
3. Stop mirroring on the sender; leave the TV on Ferry's idle waiting screen.
4. Wait past the timeout again. **Expected: the screensaver appears normally.** ← the
   case that matters most.
5. Repeat step 1, then walk the phone out of Wi-Fi range instead of disconnecting
   cleanly. **Expected: the screensaver returns after the timeout.**

---

## Security

Ferry is a **LAN-only receiver**: it opens listening sockets and parses binary protocol
data from whatever connects to them. **Run it on a home network you control**, not on
shared or open Wi-Fi. Enable **Settings → Require pairing PIN** for access control.

Full threat model and reporting process in [`SECURITY.md`](SECURITY.md).

### Audit summary

The upstream baseline was audited before any changes were made. Full writeup in
[`AUDIT.md`](AUDIT.md). The short version:

**Verdict: no malware, no backdoor, no exfiltration.**

- **Permissions** are minimal and justified. `ACCESS_FINE_LOCATION` is present and does
  look alarming, but Android gates Wi-Fi P2P (Miracast) discovery behind it — there is no
  `LocationManager`, no location provider, and no geocoding anywhere in the tree. The app
  never reads a coordinate.
- **Dependencies:** zero analytics, zero ad SDKs, zero crash reporters. The only vendor
  SDK is the Google Cast receiver, scoped to `googletv`. Verified absent from the Fire TV
  APK — all 10 dex files scanned, **zero** Cast references.
- **Egress:** no hardcoded remote endpoint exists anywhere in the codebase. There is
  exactly one outbound HTTP call site, and it targets the *sender's* own mDNS-resolved
  LAN address — that is DACP, the reverse-control channel letting your TV remote pause
  your iPhone. The host is never a constant.
- **Dynamic code:** two `System.loadLibrary` calls, for libraries built from source in
  this repo. Nothing else — no reflection on network classes, no `Runtime.exec`, and no
  `Base64.decode` call sites at all, so there is no decode-and-execute pattern.
- **The FairPlay native code** was compared **byte-for-byte** against RPiPlay and
  cross-checked against UxPlay. Five of seven files hash identical to upstream —
  **including the 483 KB lookup-table header and the 21 KB cipher core**, which is
  exactly where a payload could realistically hide. The two that differ carry only
  strict-aliasing and unaligned-access fixes, quoted in full in the audit. They add no
  behavior.
- **Both JNI bridges** validate array lengths against attacker-controlled input before
  touching native memory.

Not covered: no fuzzing was performed, and Apple's ALAC decoder and the
reverse-engineered FairPlay C were not audited internally. The mitigation is the bounds
checking at the JNI boundary.

**One thing static analysis cannot prove** is whether the running app is genuinely
internet-free. You can settle that in five minutes: block the Fire TV's WAN access at
your router, leave LAN intact, and mirror. If it works, the claim is confirmed by
observation rather than by trusting a source review.

---

## Features

Inherited from upstream, unchanged:

- **AirPlay 2 screen mirroring** from macOS 12+ and iOS/iPadOS 16+ — H.264 hardware
  decode via `MediaCodec`.
- **Audio** — AAC-ELD / AAC-LC / ALAC, with NTP-based A/V sync.
- **HomeKit-style pairing**, with an optional on-screen PIN.
- **Photos** via AirPlay, and **video URL mode** (`POST /play`).
- **DACP reverse remote** — the TV remote can play/pause/skip what the sender is playing.
- Miracast and Google Cast receivers are partially implemented upstream (control plane
  done, media pending). Ferry has not worked on these.

---

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — how a session works end to end, the JNI
  boundary, and where session state lives. Start here before modifying anything.
- [`AUDIT.md`](AUDIT.md) — the full security audit.
- [`SECURITY.md`](SECURITY.md) — threat model and vulnerability reporting.
- [`NOTICE`](NOTICE) — provenance and licensing.
- [`PROGRESS.md`](PROGRESS.md) — build log for the fork.
- [`docs/`](docs/) — upstream's specs and guides, kept for reference.

## License

**GPLv3.** See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Apple's ALAC decoder in `app/src/main/cpp/alac/` is Apache 2.0 and retains its own
headers.

## Trademarks

AirPlay, Apple, and macOS are trademarks of Apple Inc. **Ferry is not affiliated with,
authorized by, endorsed by, or sponsored by Apple Inc.** Ferry implements a protocol
compatible with AirPlay; it does not use the AirPlay name as its own.
