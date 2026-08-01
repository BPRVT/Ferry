# Ferry — build log

Running record of what happened, appended as each phase finished. Newest at the
bottom. Decisions I made on my own initiative are marked **[call]**.

---

## Phase 0 — Toolchain (not in the plan, but the Mac was bare)

This machine had **no Java, no Android SDK, and no Homebrew**. Installing Homebrew
needs an admin password, which I can't supply, so **[call]** I installed everything
into your home directory instead — no `sudo`, nothing touched outside these paths:

| Component | Version | Path |
|---|---|---|
| Temurin JDK | 17.0.20+8 (x64) | `~/ferry-toolchain/jdk/jdk-17.0.20+8/Contents/Home` |
| Android cmdline-tools | 11076708 | `~/ferry-toolchain/sdk/cmdline-tools/latest` |
| Platform | android-35 | `~/ferry-toolchain/sdk/platforms` |
| Build tools | 35.0.0 | `~/ferry-toolchain/sdk/build-tools` |
| NDK | 28.2.13676358 | `~/ferry-toolchain/sdk/ndk` |
| CMake | 3.22.1 | `~/ferry-toolchain/sdk/cmake` |
| platform-tools (`adb`) | latest | `~/ferry-toolchain/sdk/platform-tools` |

Versions were dictated by the project, not chosen: `app/build.gradle.kts` pins
`ndkVersion = "28.2.13676358"`, `compileSdk = 35`, CMake `3.22.1`, and Java 17
source/target. Total ~3.4 GB in `~/ferry-toolchain/`.

Your Mac is **x86_64** (Intel, macOS 13.6.1), so the x64 JDK is correct.

To use this toolchain in a fresh shell:

```bash
export JAVA_HOME=~/ferry-toolchain/jdk/jdk-17.0.20+8/Contents/Home
export ANDROID_HOME=~/ferry-toolchain/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`local.properties` (git-ignored) was written with `sdk.dir` pointing at the SDK.

**To remove all of it later:** `rm -rf ~/ferry-toolchain` — nothing else on the system
was modified.

---

## Phase 1 — Security audit → `AUDIT.md`

**Verdict: no malware, no backdoor, no exfiltration. No stop condition triggered.**
Full reasoning in [`AUDIT.md`](AUDIT.md); highlights only here.

**What I found:**

- **Permissions** — minimal and justified. `ACCESS_FINE_LOCATION` /
  `ACCESS_COARSE_LOCATION` are on your red-flag list and *are* present, but they're
  there because Android gates Wi-Fi P2P (Miracast) discovery behind them. There is no
  `LocationManager`, no `FusedLocationProvider`, no geocoding anywhere in the tree —
  the app never reads a coordinate. Three exported components, all justified
  (`MainActivity` must be exported to carry `LEANBACK_LAUNCHER`).
- **Dependencies** — zero analytics, zero ad SDKs, zero crash reporters. The only
  dependency that talks to a vendor is `play-services-cast-tv`, and it's scoped
  `googletvImplementation`, so it is **not in the Fire TV APK at all**.
- **Egress** — **no hardcoded remote endpoint exists in the codebase.** Exactly one
  outbound HTTP call site (`DacpClient.kt:67`), and it targets the *sender's* own
  mDNS-resolved LAN address — that's DACP, the reverse-control channel that lets your
  TV remote pause your iPhone. Host is never a constant.
- **Dynamic code** — two `System.loadLibrary` calls (`playfair`, `alac`), both built
  from source in this repo. Nothing else. No reflection on network classes, no
  `Runtime.exec`, and **no `Base64.decode` call sites at all**, so there's no
  decode-and-execute pattern to worry about.
- **`playfair/`** — the important one. Byte-level sha256 diff against `FD-/RPiPlay`,
  cross-checked against `FDH2/UxPlay` (which ship identical playfair sources, giving
  two independent baselines).

  **Five of seven files are byte-for-byte identical to upstream — including the
  483 KB `omg_hax.h` lookup table and the 21 KB `hand_garble.c`.** Those are precisely
  the files where a payload could realistically hide, and a hash match rules that out.

  `modified_md5.c` (+450 B) and `sap_hash.c` (+198 B) differ. Both diffs do the same
  thing: replace upstream's `(uint32_t*)` casts over a `unsigned char*` with a union +
  `memcpy`. That's a fix for a **strict-aliasing violation and unaligned-access
  hazard** — real latent UB in upstream, not new behavior. Output is bit-identical on
  all four shipped little-endian ABIs. The extra bytes are entirely comments and local
  declarations. **[call]** Judged benign → not the "material not present upstream"
  stop condition, so I continued. Full diffs quoted in `AUDIT.md`.
- **JNI** — both bridges validate array lengths against attacker-controlled input
  *before* touching native buffers (`fairplay_jni.c` checks 164/72; `alac_jni.cpp`
  checks input length, output capacity, and rejects `bitDepth != 16`). Correct release
  modes on every path including error paths. Nothing needed fixing.
- **Build provenance (1e)** — there is **no release workflow and no tag trigger**. CI
  builds *debug* APKs only, 7-day retention. So any upstream release APK was
  hand-uploaded with no verifiable link to source. Not evidence of wrongdoing, but it's
  the gap Phase 6 closes for Ferry.

**Recommendation deferred, not done:** drop the two location permissions from the Fire
TV flavor if you don't want Miracast. It changes behavior, and Phase 3 needs an
unmodified baseline first.

**The one thing source review can't settle:** whether the running app is genuinely
internet-free. `AUDIT.md` §1f has the runtime checklist — the decisive test is
blocking the Fire TV's WAN at the router and confirming mirroring still works.

---

## Phase 2 — Standalone repo and licensing

Directory renamed `phairplay-clean/` → `ferry/`, fresh `git init`, one commit
containing the baseline plus the licensing corrections. Not a GitHub fork; every
attribution obligation preserved in `NOTICE`.

- `LICENSE` — replaced with the full GPLv3 text (674 lines, canonical from gnu.org).
- `NOTICE` — full derivation chain, the licensing reasoning, Apple's ALAC Apache 2.0
  notice reproduced, and a trademark disclaimer.
- `cpp/playfair/*.{c,h}` — **all 7 files** given GPLv3 headers naming their origin.
  Code otherwise untouched.
- `cpp/alac/*` — **not touched.** Apple's Apache 2.0 headers preserved verbatim.

### One factual correction to the brief

You had it that upstream "pulled GPL-lineage code into an Apache-2.0 repo with the
headers stripped." I checked, and **the headers were never there to strip.**
RPiPlay's own copies of these files carry no per-file license header either — I
fetched them and confirmed. The omission exists at RPiPlay and was inherited, not
introduced by PhairPlay.

**This does not change the conclusion** — I'm not re-litigating GPLv3, and it's
still correct. RPiPlay conveys its license at the project level (`LICENSE` = GPLv3),
which binds the files inside it whether or not each one repeats the notice. I'm
flagging it only because "upstream stripped the headers" is an accusation, and it
isn't true. It reads as inherited sloppiness rather than anything deliberate.

### And the provenance is more specific than "RPiPlay"

**[call]** While verifying, I traced it one level further back than the brief did.
RPiPlay's README credits its `lib/playfair` directory to
**[EstebanKubata/playfair](https://github.com/EstebanKubata/playfair)** — "License:
GNU GPL". I checked that repo: **GPLv3**.

So the real chain is:

```
EstebanKubata/playfair (GPLv3)  →  FD-/RPiPlay (GPLv3)  →  FDH2/UxPlay  →  PhairPlay  →  Ferry
```

Your GPLv3 call is now backed by the licence at the *root* of the chain, not just
inferred from filenames. The added file headers name EstebanKubata as the origin.

---

## Phase 3 — Baseline build, unmodified

Built **before** any behavior change, so later breakage is unambiguously ours.

| Step | Result |
|---|---|
| `./gradlew assembleFiretvDebug` | **BUILD SUCCESSFUL in 7m 17s** — first attempt, no fixes needed |
| `./gradlew :test-runner:test` | **222 tests, 0 failures, 0 errors, 0 skipped** |
| `./gradlew :app:lintFiretvDebug` | **BUILD SUCCESSFUL** — clean under `warningsAsErrors = true` |

**Baseline APK:** `app/build/outputs/apk/firetv/debug/app-firetv-debug.apk` — 15 MB
sha256 `c23f71d60cc7206804a7c1cc7561a4b725ab555576deb9fdb07b65c36f549221`

Both native libraries built for all four ABIs (`libplayfair.so`, `libalac.so` ×
`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`).

**Audit item 1f.3 closed empirically:** I checked all 10 dex files in the Fire TV APK
for Google Cast references — **zero**. The `googletvImplementation` scoping does what
the audit predicted; no Play Services code ships in your binary.

---

## Phase 4 — Architecture map, then the screensaver fix

`ARCHITECTURE.md` written — session lifecycle end to end, the JNI boundary explained
in more depth than the Kotlin, and a full catalogue of everywhere session state lives.

### Diagnosis — it was the simplest candidate

Of the four you listed, it's #1: **`FLAG_KEEP_SCREEN_ON` was never set.** Not set on
the wrong window, not defeated by the surface — a repo-wide search for
`FLAG_KEEP_SCREEN_ON`, `keepScreenOn`, `WakeLock`, `PowerManager`, and `SCREEN_BRIGHT`
returned **zero hits**, and `WAKE_LOCK` wasn't even declared in the manifest.

So the "foreground service holds only a `PARTIAL_WAKE_LOCK`" theory is out too — the
service held no wake lock of any kind. Mirroring renders straight to a `SurfaceView`
with no user input, so the OS sees an idle device and runs its normal idle timer.

### The fix

`MainActivity.updateOverlay()` now sets/clears `FLAG_KEEP_SCREEN_ON`.

**Why the flag over a `SCREEN_BRIGHT_WAKE_LOCK`** (justified in the commit message
too): it's scoped to the window, so the system drops it automatically if the app
crashes or is killed. A leaked wake lock pins the TV awake until reboot — worse than
the original bug. It also needs no `WAKE_LOCK` permission, so the permission set is
unchanged, and `SCREEN_BRIGHT_WAKE_LOCK` has been deprecated since API 17.

**Driven off existing state, with no second source of truth.** `updateOverlay()` was
already the one function that knows whether anything is happening — its `else` branch
is exactly "nothing active". I extracted that condition into `isSessionActive()` and
made *both* the overlay choice and the keep-awake flag read from it, so they can't
drift apart. **[call]** A displayed pairing PIN counts as active — a screensaver over
the PIN would make pairing impossible.

**Every transition handled.** The one implementations forget — sender walks out of
Wi-Fi range — needed no special case: the RTSP client handler's `finally` block
already runs on the socket exception and funnels into the same `onStreamingStopped()`
→ `ADVERTISING` transition as a clean `TEARDOWN`. App backgrounded: the flag only has
effect while the window is visible, so normal timeout resumes and the flag reapplies
on return. App killed: the window dies and takes the flag with it.

**Tests:** `SessionActivityTest`, 11 cases, all passing. They call the production
predicate directly rather than restating its logic — the existing `MainActivityTest`
copy-pastes the logic it tests, which is exactly the drift I wanted to avoid. The idle
case (your primary acceptance criterion) is tested from every direction, including
across all non-`CONNECTED` protocol states.

**Fire OS caveat — genuinely undetermined.** Whether the standard flag suffices on
Fire OS specifically **cannot be answered from source**, so I documented it in the
README rather than guessing or hacking around it. `FLAG_KEEP_SCREEN_ON` is the correct
API and works on stock Android TV; Fire OS is a fork whose screensaver is a
system-level feature with its own timer. The README tells you how to test it in two
minutes and what to do if the OS overrides us (set the device screensaver to Never).
I deliberately did **not** fake input events or hold a screen-bright lock to force it.

---

## Phase 5 — Rebrand to Ferry

Display name **Ferry**, package **`com.ferry.receiver`**, repo **ferry**, advertised
device name **Ferry**.

**[call]** Fire TV gets the unsuffixed application ID `com.ferry.receiver` since it's
your primary target; googletv keeps `com.ferry.receiver.googletv` so both flavors can
coexist on one device.

### The part that would have silently broken

**JNI symbol names encode the Java package.** Renaming the package without renaming
the exported C functions compiles perfectly and then throws `UnsatisfiedLinkError` at
runtime — the first time a sender attempts the FairPlay handshake, i.e. the first time
you actually tried to mirror. All four entry points were renamed:

```
Java_com_phairplay_airplay_handshake_FairPlay_nativePlayfairDecrypt
  → Java_com_ferry_receiver_airplay_handshake_FairPlay_nativePlayfairDecrypt
Java_com_phairplay_airplay_handshake_AlacDecoder_{nativeInit,nativeDecode,nativeRelease}
  → Java_com_ferry_receiver_airplay_handshake_AlacDecoder_{...}
```

I verified this against the **built binaries** rather than trusting the source edit —
`llvm-nm` on the arm64-v8a `libplayfair.so` and `libalac.so` shows exactly those four
symbols exported, matching the Kotlin `external fun` declarations.

`cpp/playfair/` and `cpp/alac/` were excluded from the rename sweep so their license
and provenance headers survive verbatim — including the comment in
`alac/EndianPortable.c` crediting **PhairPlay** with the ARM detection fix. That's
accurate attribution of who made that change, so it stays.

### Advertised device name

**[call]** Now defaults to `Ferry` rather than the TV's system device name. You asked
for the advertised name to be `Ferry` with no suffix or version, and this is the
string that shows up in Control Center next to real Apple devices. **Settings → Device
Name still overrides it** — enter your TV's own name there to get the previous
behavior. Flagging it because it's a visible behavior change, not just a string swap.

### Trademark

"AirPlay" appears in **no** app name, package ID, repo name, launcher label, or
advertised service name — verified. Descriptive prose in the README is retained.
Disclaimers added to README and `NOTICE`.

### One build fix

The rebrand build failed once: `PhotoFrame` is declared at the bottom of
`FerryService.kt`, which the JVM `test-runner` module excludes (it needs
`NotificationCompat` and the generated `R` class). My new `isSessionActive()`
references `PhotoFrame`, so test-runner couldn't compile. Fixed by moving `PhotoFrame`
into its own file, **verbatim and unchanged** — I briefly added `equals`/`hashCode` to
it and then reverted that, because changing value semantics would have altered
`StateFlow` de-duplication behavior during what should be a pure move.

Also: placeholder launcher icons and a 1280×720 banner (ferry on water, dark palette),
generated by `tools/generate-artwork.py` so you can tweak and regenerate. README fully
rewritten.

---

## Phase 6 — Ship

**Release APK:** `~/Desktop/Ferry/ferry-1.0.0-firetv.apk` — 11 MB (R8-minified,
resources shrunk), **signed and verified**.
sha256 `a04025053c72d015730f66d70404fcd9289d2284f64a39a87bbe4337a4dcfc0f`

**[call] Signing key.** The release build produces an *unsigned* APK (the signing
config is env-var driven and no keystore existed), and Android will not install an
unsigned APK. So I generated a local 4096-bit dev keystore at
`~/ferry-toolchain/ferry-dev.keystore` and signed with it, so you can sideload
tonight. **It is deliberately outside the repo and is not committed.** It's a
throwaway — generate a real one before publishing releases, and note that changing
signing keys later requires uninstalling first.

> **Retired.** This dev key signed v1.0.0 only. Its password was originally written
> out in this file, so it must be treated as compromised; it is superseded by the
> release key described in `tools/new-release-key.sh`. Never record a keystore
> password in the repository — the whole point of the key is that only you hold it.

**Release APK verified after minification** (R8 can strip things that only JNI
references): package `com.ferry.receiver`, label `Ferry`, leanback launcher activity
present, both native libs present for all four ABIs, `FairPlay` and `AlacDecoder`
classes retained, and **zero** Google Cast references.

**CI:** added `.github/workflows/release.yml` — builds the Fire TV APK from source on
a `v*` tag, runs tests and lint first, records the commit SHA and artifact sha256 in
the job summary and release notes, and publishes a **draft** release. Signing is
optional and entirely secret-driven; with no secrets configured it still succeeds and
publishes unsigned. This closes the provenance gap noted in Phase 1e.

**Repo hygiene** — verified, not assumed: no keystores, APKs, `local.properties`, or
build output tracked; no absolute machine paths in tracked files; no hardcoded secrets
in CI.

### Still outstanding — for you, not me

1. **The WAN-block test** (`AUDIT.md` §1f.1). Five minutes, and it's the only way to
   confirm the "zero internet required" claim by observation rather than by trusting a
   source review.
2. **The screensaver acceptance test** — especially step 4, confirming the screensaver
   *does* come back once you disconnect.
3. **Replace the dev signing key** before publishing any release.
4. **Optional:** drop `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` from the Fire
   TV flavor if you don't want Miracast (Phase 1 recommendation, deliberately not done
   since it changes behavior).
