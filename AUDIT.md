# Security Audit — PhairPlay baseline (pre-fork)

**Audited tree:** `phairplay-clean/`, extracted from `mazer666/PhairPlay`, git history stripped.
**Audit date:** 2026-08-01
**Auditor:** static source review + byte-level diff against upstream provenance.
**Scope:** everything shipped in the `firetv` and `googletv` flavors.

**Verdict up front: no malware, no backdoor, no exfiltration. Safe to run on a home
network.** Detail and caveats below.

---

## 1a. Manifest and permissions

Permissions are declared once in `app/src/main/AndroidManifest.xml`; the `googletv`
flavor manifest adds no permissions of its own (only Cast intent filters and a
receiver-options `meta-data`). The `firetv` flavor has no manifest at all, so it
inherits `main` unchanged.

| Permission | Verdict | Why it's there |
|---|---|---|
| `INTERNET` | Expected | TCP/UDP sockets. On Android this is required even for LAN-only sockets — it is not evidence of internet use. |
| `ACCESS_NETWORK_STATE` | Expected | Network-change detection for reconnect. |
| `ACCESS_WIFI_STATE` | Expected | Reads local IP/MAC to advertise over mDNS. |
| `CHANGE_WIFI_MULTICAST_STATE` | Expected | Joins the mDNS multicast group (224.0.0.251). Without it Bonjour discovery silently fails. |
| `FOREGROUND_SERVICE` | Expected | Receiver must survive backgrounding. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Expected | Android 14+ requires the typed variant matching `foregroundServiceType="connectedDevice"`. |
| `RECEIVE_BOOT_COMPLETED` | Expected | Optional "start on boot" setting. |
| `POST_NOTIFICATIONS` | Expected | Android 13+ runtime grant for the foreground-service notification. |
| `CHANGE_WIFI_STATE` | Expected *for Miracast* | `WifiP2pManager` requires it. |
| **`ACCESS_FINE_LOCATION`** | **Flagged — justified, but removable** | See below. |
| **`ACCESS_COARSE_LOCATION`** | **Flagged — justified, but removable** | See below. |
| `NEARBY_WIFI_DEVICES` | Expected | The Android 13+ replacement for the location permissions, for P2P. |

### The location permissions

`ACCESS_FINE_LOCATION` is on the prompt's genuine-red-flag list, so it deserves a
straight answer: **it is present for a real technical reason, not for tracking.**

Android gates Wi-Fi P2P / Wi-Fi scanning behind location permissions, because a
list of nearby Wi-Fi networks is itself a location fingerprint. Any app doing
Miracast discovery must hold it. There is **no** `LocationManager`,
`FusedLocationProviderClient`, `getLastKnownLocation`, or geocoding call anywhere in
the tree — the permission is held for the P2P API's benefit and the app never reads a
coordinate.

**Recommendation (carried into Ferry):** it is still worth removing from the Fire TV
flavor if Miracast isn't wanted. A permission you don't grant is one you don't have to
trust. Noted in `PROGRESS.md`; not done as part of this audit because it changes
behavior and Phase 3 requires an unmodified baseline first.

### Exported components

| Component | `exported` | Justification |
|---|---|---|
| `.MainActivity` | `true` | **Required.** It carries `LEANBACK_LAUNCHER`; an unexported launcher activity cannot be launched from the TV home screen. It accepts no data URIs and reads no `Intent` extras from the caller, so an external `am start` gains nothing beyond "the app opens". |
| `.service.PhairPlayService` | `false` | Correct. Started only via in-process `ServiceController`. |
| `.service.BootReceiver` | `false` | Correct. `BOOT_COMPLETED` is a protected system broadcast, deliverable to unexported receivers. Third-party apps cannot spoof it. |
| `.MainActivity` (googletv) | `true` | Adds Cast `LAUNCH`/`LOAD` intent filters — required by the Cast Connect SDK. googletv flavor only. |

No `provider`, no exported `receiver` with a custom action, no `android:permission`
gaps. `android:allowBackup="false"` is set — good; pairing keys don't get swept into
cloud backup.

---

## 1b. Dependencies

Full set from `gradle/libs.versions.toml` and `app/build.gradle.kts`:

| Dependency | Egress? | Note |
|---|---|---|
| `androidx.appcompat`, `constraintlayout`, `core-ktx`, `leanback` | No | UI only. |
| `androidx.datastore-preferences` | No | Local file-backed settings. |
| `kotlinx-coroutines-android` | No | |
| `com.jakewharton.timber` | No | Logging facade. Writes to logcat only — the app installs no remote logging tree (verified in `PhairPlayApp.kt` / `util/Logger.kt`). |
| `org.bouncycastle:bcprov-jdk18on` | No | Pure crypto provider (AES-CTR, SRP-6a). No network code. |
| `com.googlecode.plist:dd-plist` | No | Apple bplist parser, pure Java. |
| `com.google.android.gms:play-services-cast-tv` | **Yes — googletv flavor only** | See below. |
| junit / mockk / robolectric / espresso | No | Test-only, not in the APK. |

**Analytics/egress SDK check: none present.** No Firebase, Crashlytics, Sentry,
Segment, Amplitude, AppsFlyer, no ad SDK, no attribution SDK.

**The one caveat — `play-services-cast-tv`:** the Google Cast receiver SDK does talk
to Google. It is scoped with `"googletvImplementation"(...)`, so **it is not compiled
into the Fire TV APK at all** — Fire TV lacks Play Services and can't run it. Since
you're building `firetv`, the shipped binary has no Google SDK in it. Confirm after
build with `apkanalyzer`; the `PROGRESS.md` Phase 3 entry records the check.

---

## 1c. Egress and dynamic code

### Hardcoded URLs / IPs / domains

Sorted as requested:

**(a) Local-network protocol constants** — the only category with any runtime weight.
Port numbers, `224.0.0.251` style mDNS constants, and `rtsp://` URIs constructed from
the *peer's* address at runtime. Nothing dials a fixed host.

**(b) Comments and docs** — all `http://www.apache.org/licenses/LICENSE-2.0` hits
(15 of them) are Apple's license headers in `alac/`. `192.168.x.x` literals appear
only in test fixtures (`SdpParserTest`, `RtspHandlerTest`, `SenderNameExtractionTest`,
`MiracastReceiverTest`) and KDoc examples. Not shipped, not dialed.

**(c) Real remote endpoints: NONE.**

There is exactly one place the app makes an outbound HTTP request —
`airplay/DacpClient.kt:67`:

```kotlin
val conn = URL("http://$host:$port/ctrl-int/1/$command").openConnection() as HttpURLConnection
```

This is **DACP**, the AirPlay reverse-control channel, and it is worth explaining since
it's protocol-specific. When your iPhone or Mac starts a session it sends two RTSP
headers: `DACP-ID` (naming a Bonjour service it is itself hosting, as
`iTunes_Ctrl_<DACP-ID>`) and `Active-Remote` (a per-session auth token). If you press
play/pause on the TV remote, the receiver resolves that `_dacp._tcp` service **over
mDNS on the local link** and issues a GET to it with the token echoed back.

So: `host` is never a constant. It is whatever address mDNS resolved for the sender
that is currently connected to you — i.e. your own phone, on your own LAN. There is no
path by which this reaches the internet. Timeouts are bounded (2s/2s) and failures are
swallowed and logged.

### Dynamic code loading

Grepped for `DexClassLoader`, `PathClassLoader`, `Class.forName`, `Runtime.exec`,
`ProcessBuilder`, `createPackageContext`, `getDeclaredMethod`, `System.load`.

**Exactly two hits in the entire codebase, both expected:**

```
handshake/FairPlay.kt:78     System.loadLibrary("playfair")
handshake/AlacDecoder.kt:68  System.loadLibrary("alac")
```

Both name libraries built from source in this repo by `cpp/CMakeLists.txt`. Nothing
else loads native code. No reflection on network classes. **No `Base64.decode` call
sites at all** — so there is no decode-then-execute blob pattern anywhere.

---

## 1d. The `playfair/` directory — diff against known-good upstream

This is the part that matters most, so it got the most rigorous treatment: a
**byte-level hash comparison** against `FD-/RPiPlay@master`, cross-checked against
`FDH2/UxPlay@master`.

First, the cross-check: **RPiPlay and UxPlay ship byte-identical playfair sources** for
all six common files. That gives two independent confirmations of what "upstream"
means rather than one.

| File | Local | Upstream | Result |
|---|---:|---:|---|
| `playfair.c` | 1025 | 1025 | **IDENTICAL** (sha256 match) |
| `playfair.h` | 145 | 145 | **IDENTICAL** |
| `omg_hax.c` | 21648 | 21648 | **IDENTICAL** |
| `omg_hax.h` | 483271 | 483271 | **IDENTICAL** |
| `hand_garble.c` | 20325 | 20325 | **IDENTICAL** |
| `modified_md5.c` | 3909 | 3459 | Modified (+450 B) |
| `sap_hash.c` | 3555 | 3357 | Modified (+198 B) |

**Result: MODIFIED — and every modification is a benign portability fix.**

Note first what this rules out. The two files most likely to hide something — the
483 KB `omg_hax.h` lookup table and the 21 KB `hand_garble.c` — are **byte-for-byte
identical to upstream**. A hidden payload in a large opaque blob is the realistic
threat model here, and a sha256 match eliminates it. The prompt was right that these
files look alarming; they are also provably unmodified.

### The two diffs, in full

Both changes do the same one thing: upstream reads 32-bit words out of a
`unsigned char*` by casting the pointer to `uint32_t*`. That is a **strict-aliasing
violation** and an **unaligned-access hazard** in C. It happens to work on x86, and
happens to work on ARM for aligned buffers, but it is undefined behavior that modern
compilers at `-O2` are entitled to miscompile. On a JNI byte array whose alignment you
don't control, it's a genuine (if latent) bug.

**`modified_md5.c`** replaces the casts with a union:

```c
union { unsigned char bytes[64]; uint32_t words[16]; } block;
uint32_t key_words[4];
uint32_t out_words[4];
memcpy(block.bytes, originalblockIn, 64);
memcpy(key_words, keyIn, sizeof(key_words));
```

and writes the result back explicitly at the end:

```c
memcpy(keyOut, out_words, sizeof(out_words));
```

The union is the standard well-defined way to alias the same 64 bytes as both a byte
array and a word array, which this algorithm genuinely needs (it reads the block
byte-wise for `input`, and swaps it word-wise at `i == 31`).

**Semantic equivalence:** upstream already `memcpy`'d into a local `blockIn[64]`, so
the word-swaps were already operating on a copy — behavior is unchanged. `key_words`
is read once at the top and never written, so copying it in is equivalent.
`out_words[0..3]` are assigned only at the very end, so accumulating locally and
`memcpy`ing once produces the same bytes the old direct-pointer writes did. On any
little-endian target (all four shipped ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`,
`x86_64`) the output is bit-identical to upstream.

**`sap_hash.c`** applies the identical treatment, plus adds `#include <string.h>`
(needed for the new `memcpy`) and fixes trailing whitespace. `sap_hash` never writes
back through `blockIn`, so reading from a local copy is equivalent.

### Assessment

Both diffs are **semantically neutral, defensively motivated, and add no
functionality**. The +648 bytes are entirely comments and local variable
declarations. There is no added I/O, no added branching on input data, no new
symbols. This is **not** the "contains material not present upstream" case — nothing
here is new *behavior*, so the Phase 1d stop condition is **not** triggered.

I'd go further: these are improvements, and upstream RPiPlay would be better off
taking them.

### JNI entry points — attacker-controlled input

This parses packets from anything on the LAN, so bounds handling matters regardless of
intent. Both bridges were reviewed line by line.

**`fairplay_jni.c` → `nativePlayfairDecrypt` — correctly hardened.** `playfair_decrypt`
reads fixed offsets: `cipherText[16..31]` and `cipherText[56..71]` (so it needs 72
bytes) and a 164-byte key message. The bridge checks both before calling:

```c
if ((*env)->GetArrayLength(env, keyMessage) < KEY_MESSAGE_LEN ||   // 164
    (*env)->GetArrayLength(env, cipher) < CIPHER_LEN) {            // 72
    return NULL;
}
```

Null checks on both arrays, null checks on both `GetByteArrayElements` results,
`JNI_ABORT` release on every path including the error paths, and a null check on the
`NewByteArray` result before `SetByteArrayRegion`. Output is a fixed 16-byte stack
buffer written by a function that writes exactly 16 bytes. **No overflow reachable
from a malformed peer message** — a short array yields `null`, which surfaces as a
clean Kotlin-side failure.

**`alac_jni.cpp` → `nativeDecode` — correctly hardened.** Validates `inputLen > 0`,
`inputLen <= GetArrayLength(input)` (so a lying length can't over-read), rejects
`numChannels == 0` / `frameLength == 0`, and — the important one — checks the output
array is large enough *before* decoding:

```cpp
if (outCap < static_cast<jsize>(frameLength * numChannels * 2)) return -1;
```

`nativeInit` additionally rejects any cookie whose `bitDepth != 16`, which is what
stops a crafted magic cookie from making the decoder write wider samples than the
Kotlin side sized the buffer for. Release modes are correct (`JNI_ABORT` on input and
on the error path, `0` to commit output only on success).

**Residual risk — inherent, not a defect:** `ALACDecoder::Decode` is Apple's code and
`omg_hax`/`hand_garble` are reverse-engineered; neither was written with hostile input
in mind, and neither was re-audited internally here (that would be a fuzzing exercise,
not a source review). The JNI layer bounds what reaches them, which is the right
mitigation and is correctly implemented. Attack surface is limited to devices already
on your LAN.

**No fixes were needed.** Nothing trivial was left outstanding.

---

## 1e. Build provenance

`.github/workflows/` contains `ci.yml` and `lint.yml`. Both run on push/PR to `main`.

`ci.yml` runs JVM tests, then lint + `assembleGoogletvDebug` / `assembleFiretvDebug`
across a flavor matrix, and uploads the **debug** APKs as artifacts with a 7-day
retention.

**There is no release workflow and no tag trigger.** Nothing builds a release APK,
nothing signs one, and nothing publishes to GitHub Releases.

**What that implies:** any release APK distributed by upstream was **built on a
maintainer's machine and hand-uploaded**. That is not evidence of wrongdoing — it is
how most small projects work — but it means a published binary has *no verifiable link
to the source in the repo*. You cannot confirm from the outside that a downloaded APK
was built from the commit it claims. CI artifacts are debug-only and expire in a week.

This is exactly the gap Phase 6 closes: Ferry gets a tag-triggered release workflow so
its binaries have provenance this baseline lacks. And it's moot for you personally the
moment you build from source, which is what we're doing.

Two smaller notes: CI has no secrets and no keystore (signing config is env-var driven
and simply absent, producing unsigned release APKs), and `actions/checkout@v4` etc. are
pinned to major tags rather than commit SHAs — conventional, slightly weaker than SHA
pinning.

---

## 1f. Runtime checklist — what static analysis cannot prove

Source review proves what the code *can* do. It cannot prove what a running binary
*does* — timing, actual sockets opened, or behavior of the prebuilt `.so` files (moot
here, since we compile them from the audited source ourselves). Verify these
on-device:

1. **Block the Fire TV's WAN access at the router, then mirror.** ← *the decisive test.*
   Add a firewall rule denying the Fire TV's MAC/IP any outbound internet, keeping LAN
   intact. Then mirror from your iPhone. **If mirroring works fully — video, audio,
   remote control — the "zero internet required" claim is confirmed by observation, not
   by my reading of the source.** If anything breaks, that falsifies it and is worth
   investigating. My static prediction: it will work perfectly.

2. **Watch the traffic.** With the app idle and again mid-session, check your router's
   connection log (or `tcpdump`) for any flow whose destination is outside your subnet.
   Expect: mDNS to `224.0.0.251:5353`, RTSP/HTTP on the receiver's own ports, RTP/UDP,
   NTP timing to the *sender*, and DACP back to the sender. Expect **nothing** leaving
   the LAN. DNS lookups for non-local names would be the specific thing to flag.

3. **Confirm no Google SDK in the Fire TV APK.** `play-services-cast-tv` should be
   absent from the `firetv` build. Verify on the built artifact rather than trusting
   the flavor config.

4. **Check the foreground-service notification** says what it should and that stopping
   the service actually tears down the listeners (no lingering advertised service —
   confirm with a Bonjour browser that `Ferry` disappears from the AirPlay menu).

5. **Boot behavior.** If you enable "start on boot", confirm it starts *only* the
   receiver and doesn't reach the network before you expect.

6. **Screensaver acceptance test** (Phase 4) — the one regression that matters most:
   start a session, wait past your screensaver timeout, confirm nothing happens;
   disconnect, wait again, confirm the screensaver **does** come back. An app that pins
   the TV awake forever is a worse bug than the one being fixed.

---

## Verdict

**No malware. No backdoor. No exfiltration. No stop condition triggered.**

- **Permissions:** minimal and justified. The two location permissions are real
  red-flag candidates that turn out to be Wi-Fi-P2P plumbing, with no location API used
  anywhere. Removable if you drop Miracast.
- **Dependencies:** clean. Zero analytics or ad SDKs. The single Google SDK is scoped
  out of the Fire TV build.
- **Egress:** the app has exactly one outbound HTTP call, and it targets the connected
  sender's own mDNS-resolved LAN address. No hardcoded remote endpoint exists in the
  tree.
- **Dynamic code:** two `System.loadLibrary` calls for libraries built from source in
  this repo. Nothing else. No reflection, no exec, no base64-decoded payloads.
- **`playfair/`:** five of seven files byte-identical to RPiPlay *and* UxPlay,
  including both large opaque ones. The two diffs are strict-aliasing hardening,
  semantically equivalent, adding no behavior.
- **JNI:** both bridges validate lengths on attacker-controlled input before touching
  native buffers. Better than I expected going in.

The one thing this audit genuinely *cannot* settle from source is item 1f.1 — whether
the running app is truly internet-free. Block WAN at the router and mirror; that test
either confirms it or falsifies it in five minutes, and it's worth doing.

The real caveat isn't malice, it's **attack surface**: this app opens listening sockets
that parse complex binary protocols from any device on your LAN, and some of the
parsing happens in reverse-engineered C. That's inherent to being an AirPlay receiver,
and the bounds checking around it is done correctly. Run it on a network you trust.
