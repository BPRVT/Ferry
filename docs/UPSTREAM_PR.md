# Draft PR description — screensaver fix, for submission to mazer666/PhairPlay

Copy the section below into a pull request against upstream. It describes only the
screensaver change; none of Ferry's rebranding or relicensing is included, and the patch
applies to upstream's `com.phairplay` package as-is (rename `isSessionActive`'s package
if you move the file).

The commit to cherry-pick is the one titled *"Keep the display awake during an active
session"*.

---

## Fix: screensaver interrupts active mirroring sessions

### The problem

On Fire TV, the system screensaver activates during an active mirroring session and
interrupts playback. It should not be possible for the screensaver to appear while
something is actively mirroring.

### Cause

Nothing in the codebase asks for the screen to stay on. Searching the tree for
`FLAG_KEEP_SCREEN_ON`, `keepScreenOn`, `WakeLock`, `PowerManager`, and `SCREEN_BRIGHT`
returns no hits, and `WAKE_LOCK` is not declared in the manifest.

This rules out the subtler explanations — it is not a flag set on the wrong window, and
the foreground service is not holding a `PARTIAL_WAKE_LOCK` that keeps the CPU alive
while letting the display sleep. There is simply no keep-awake mechanism at all.

Mirroring renders decoded frames straight to a `SurfaceView` and involves no user input,
so from the framework's point of view the device is idle and the normal idle timer runs
to completion.

### The change

`MainActivity.updateOverlay()` sets `FLAG_KEEP_SCREEN_ON` while a session is active and
clears it when one is not.

**Why a window flag rather than a `SCREEN_BRIGHT_WAKE_LOCK`:**

- It is scoped to the window, so the system releases it automatically if the Activity is
  destroyed or the app crashes. A wake lock leaked the same way keeps the TV awake until
  reboot — a worse failure than the bug being fixed.
- It requires no `WAKE_LOCK` permission, so the app's permission set is unchanged.
- `SCREEN_BRIGHT_WAKE_LOCK` has been deprecated since API 17.

**No new session state was introduced.** `updateOverlay()` was already the single place
that knows whether anything is being received — its `else` branch is exactly the "nothing
active" case. This extracts that condition into a small pure function, `isSessionActive()`,
and has both the overlay selection and the keep-awake flag read from it, so the two cannot
disagree as state is added later.

Active means: a `CONNECTED` AirPlay session, audio-only playback (`nowPlaying`), a
displayed photo (`photoFrame`), or a pairing PIN on screen. The PIN is included
deliberately — a screensaver covering the code would make pairing impossible.

### Scoping — the important part

**When the app is idle on the waiting screen, normal screensaver and sleep behavior
resume.** An app that pins the TV awake indefinitely would be a worse bug than the one
being fixed, so that case is covered from several directions in the tests, including
across every non-`CONNECTED` `ProtocolState`.

### Transitions

| Transition | Handling |
|---|---|
| Clean end (`TEARDOWN`) | `onStreamingStopped()` emits `ADVERTISING` → flag cleared |
| **Abrupt drop** (sender leaves Wi-Fi range) | No special case needed: the RTSP client handler's `finally` block runs on the socket exception and calls the same `onStreamingStopped()` |
| App backgrounded mid-session | The flag only takes effect while the window is visible, so the normal idle timeout resumes; it reapplies on return if the session is still live |
| App killed mid-session | The window is destroyed and the flag goes with it — the property a wake lock would not have |

### Fire OS caveat

Whether the standard Android flag is always sufficient on Fire OS specifically could not
be determined from source. `FLAG_KEEP_SCREEN_ON` is the correct documented API and
prevents display timeout on stock Android TV, but Fire OS's screensaver is a system
feature with its own settings entry, and whether that timer always defers to an app
holding the flag may vary by Fire OS version.

This change deliberately does **not** attempt to work around a possible system-level
override (by injecting fake input events or holding a screen-bright lock). Those
approaches are fragile and risk leaving the display on permanently. If the OS does
override the flag on some devices, documenting it seems better than fighting it — happy
to add a note to the README if you'd prefer.

### Tests

New `SessionActivityTest` — 11 cases covering idle, each active signal individually,
and the transitions above. They call the production predicate rather than restating its
logic, so they cannot drift from the behavior they pin down.

Full suite passes: 233 JVM tests, lint clean under `warningsAsErrors = true`, and the
Fire TV debug and release APKs build.

### Manual verification

1. Start mirroring; leave it past the screensaver timeout (set the timeout to 2 minutes
   to speed this up) → mirroring continues uninterrupted.
2. Disconnect; leave the app on the idle waiting screen; wait past the timeout again →
   the screensaver appears normally.
3. Start mirroring, then move the sender out of Wi-Fi range → the screensaver returns
   after the timeout.

### Notes

`PhotoFrame` is moved out of `PhairPlayService.kt` into its own file, unchanged. The JVM
`test-runner` module excludes `PhairPlayService.kt` (it depends on `NotificationCompat`
and the generated `R` class), so the new predicate and its tests could not compile there
while `PhotoFrame` lived inside it. Happy to drop this into a separate commit if you'd
rather keep the fix minimal.
