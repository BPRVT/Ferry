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
  "can mirror to your TV."** That is the intended function. Enable PIN pairing
  (Settings → Require PIN) if you want access control.
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

## Hardening you can do

1. **Enable PIN pairing** (Settings) so an unknown device can't mirror without a code
   shown on your TV.
2. **Put the Fire TV on an IoT/guest VLAN** if you have one, separated from laptops and
   phones that hold anything sensitive.
3. **Block the Fire TV's outbound WAN access at the router.** Ferry needs no internet.
   This both hardens the device and independently verifies the "no internet required"
   claim — if mirroring still works with WAN blocked, that claim is confirmed by
   observation rather than by trusting a source review.
4. **Stop the service when you're not using it** (notification → Stop). No listener, no
   attack surface.
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
