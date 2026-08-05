# Security Policy

## Reporting a vulnerability

Please report security issues **privately** — open a [GitHub Security
Advisory](https://github.com/BPRVT/Ferry/security/advisories/new) rather than a public
issue, so a fix can ship before details are public.

Include: what you did, what happened, what you expected, the Fire TV / Android TV model
and OS version, and the Ferry version (Settings → About). A packet capture or logcat
excerpt helps enormously. If you have a proof of concept, please include it.

This is a hobby project maintained in spare time. Realistic expectations: acknowledgement
within about a week, and a fix when one is practical. There is no bug bounty.

## Threat model — read this first

Ferry is a **LAN-only receiver**. Understanding what that means is most of the security
story.

Ferry works by opening listening sockets and waiting for anything on the local network to
connect. It then parses complex binary protocol data from that peer — RTSP, binary
plists, SDP, RTP, H.264, ALAC — some of it in C that was reverse-engineered rather than
written from a specification.

**The security boundary is your local network.** Ferry assumes devices on your LAN are
not actively hostile. It is designed for a home network, and that is the only place it
should run.

**Do not run Ferry on a network you do not control** — shared student accommodation,
office guest Wi-Fi, a hotel, a conference, or anything with an open SSID. On such a
network, every device present can reach Ferry's parsers.

### In scope

- Memory-safety bugs reachable from network input (buffer overflows in the JNI bridges,
  the FairPlay code, the ALAC decoder, or the H.264/RTP parsing paths).
- Bypasses of PIN pairing when it is enabled.
- Any outbound network connection to a host outside the local subnet. Ferry should make
  none. See [`AUDIT.md`](AUDIT.md).
- Anything that lets a LAN peer execute code, read files outside the app sandbox, or
  crash the device persistently.
- Leakage of pairing keys or stream keys off-device.

### Out of scope

- **Anything requiring an attacker to already be on your LAN, where the impact is only
  "can mirror to your TV."** That is the intended function. Turn on **Settings → Require
  pairing PIN** if you want access control — see the note below, because as of 4.5.0 it is
  **off by default**.
- **The AirPlay protocol's own weaknesses.** Legacy AirPlay auth is weak by design;
  Ferry implements the protocol, it does not fix it.
- Physical access to the device, or a rooted/compromised Fire TV.
- The legal status of the reverse-engineered FairPlay implementation. That is a licensing
  question, not a vulnerability — see [`NOTICE`](NOTICE).
- Denial of service by flooding the LAN. Ferry accepts one client at a time by design.

## What has already been reviewed

The baseline this project forked from was audited before any changes were made; the full
writeup is in [`AUDIT.md`](AUDIT.md). Summary:

- **No malware, backdoor, or exfiltration.** No analytics SDKs, no ad SDKs, no crash
  reporters, no hardcoded remote endpoints anywhere in the tree.
- The FairPlay sources in `app/src/main/cpp/playfair/` were compared **byte-for-byte**
  against `FD-/RPiPlay` and cross-checked against `FDH2/UxPlay`. Five of seven files hash
  identical to upstream, including the 483 KB lookup-table header. The two that differ
  carry only strict-aliasing/alignment fixes, quoted in full in `AUDIT.md`.
- Both JNI bridges validate array lengths against attacker-controlled input before
  touching native memory.

**Not** covered by that audit, and worth being honest about: no fuzzing was done. Apple's
ALAC decoder and the reverse-engineered FairPlay C were not audited internally — the
mitigation is the bounds checking at the JNI boundary, not confidence in the code behind
it.

## The PIN default, stated plainly

**As of 4.5.0, "Require pairing PIN" is OFF by default.** Ferry ships open: any device
that can reach the subnet can mirror to the TV without anyone touching it. In that
configuration the only access control is your network perimeter.

This is a deliberate trade, not an accident. Ferry is a TV appliance driven by a remote
control, and a PIN on every connection is real friction on a network the owner already
trusts. Versions 2.0.5 through 4.0.0 defaulted it ON; 4.5.0 reverses that.

**Turn it on if any of these are true:** the TV shares a network with guests, you're on
shared, building-wide, or open Wi-Fi, or the network carries devices you don't control.
With it on, a sender must complete SRP pairing against a code shown on the TV, and
pair-setup locks out permanently after 10 failed attempts (the toggle itself is the
owner-present reset, since reaching it needs physical access to the TV).

**Upgrading from 4.0.0 or earlier does not silently disable your PIN.** Settings are
persisted as a whole record, so if you have ever changed any setting in the app, your
stored value wins and nothing changes. The new default reaches only installs that have
never written a setting — fresh installs, and users who never opened Settings.

## The receiver no longer runs while the app is closed

**Through 5.5.0, Ferry kept advertising itself indefinitely after you thought you had
closed it.** The activity stopped the service only when it was *finishing*, and Fire TV's
Home button does not finish an activity — it stops it. So the receiver stayed up: mDNS
still announcing, port 7000 still listening, and nothing on screen to say so.

Combined with the PIN default above, that is the part worth stating plainly: an
unauthenticated receiver was left on the LAN with no visible indication it was there.
Anything that could reach the subnet could put video and audio on the TV.

**As of 6.0.0, receiving is scoped to the app being open.** Leave Ferry and the receiver
stops, the mDNS registration is withdrawn, and port 7000 closes. Settings → **Keep
receiving when closed** restores the old always-on behaviour as a deliberate choice, and
"Start on boot" implies it. The service also no longer declares itself sticky, so Android
cannot silently resurrect a receiver with no app on screen and no user action.

## Hardening you can do

1. **Turn on PIN pairing** (Settings → Require pairing PIN) so an unknown device can't
   mirror without a code shown on your TV. **This is off by default** — see above.
2. **Put the Fire TV on an IoT/guest VLAN** if you have one, separated from laptops and
   phones that hold anything sensitive.
3. **Block the Fire TV's outbound WAN access at the router.** Ferry needs no internet.
   This both hardens the device and independently verifies the "no internet required"
   claim — if mirroring still works with WAN blocked, that claim is confirmed by
   observation rather than by trusting a source review.
4. **Leave "Keep receiving when closed" off** (the default as of 6.0.0), so Ferry only
   listens while it is on screen. You can also stop the service outright at any time from
   the notification → Stop. No listener, no attack surface.
5. **Build from source.** Ferry's release workflow builds APKs from a tagged commit in
   CI, so a release has verifiable provenance — but building it yourself is strictly
   better.

## Supported versions

The latest release only. This is a hobby project; there are no backported security fixes
for older versions.

## A note on the native code

`app/src/main/cpp/playfair/` is reverse-engineered FairPlay code, imported from
GPLv3 upstreams (EstebanKubata/playfair → RPiPlay). It is deliberately obfuscated —
dense bit manipulation, opaque function names like `omg_hax` and `hand_garble`, and
large hardcoded tables. **That appearance is expected and is not evidence of anything
malicious**; it is what this code looks like upstream, and the hashes confirm it matches.

If you are changing anything in `cpp/`, preserve the length checks in `fairplay_jni.c`
and `alac_jni.cpp`. They are the boundary between a malformed packet and heap
corruption, and they are the main reason a hostile LAN peer can't get further than a
failed handshake. See [`ARCHITECTURE.md`](ARCHITECTURE.md) for what each check defends.
