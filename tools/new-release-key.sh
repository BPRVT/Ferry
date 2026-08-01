#!/usr/bin/env bash
#
# Creates Ferry's real release signing key and configures the GitHub Actions
# secrets that let the Release workflow use it.
#
#   ./tools/new-release-key.sh
#
# This replaces the throwaway ferry-dev key that signed v1.0.0. That key's
# password was written into PROGRESS.md and pushed publicly, so it is treated as
# compromised and is not reused.
#
# ONE-WAY DOOR: Android refuses to update an installed app whose signing key
# changed. Everyone running a ferry-dev-signed build (v1.0.0) must uninstall
# before they can install anything signed with this new key. That is why this is
# worth doing now, at 5 installs, rather than later.
#
# The key never leaves this machine except as an encrypted GitHub Actions secret.
# Passwords are read with `read -s` (never echoed), passed to `gh` on stdin
# (never as arguments, so they stay out of the process list), and never written
# to disk in plaintext.

set -euo pipefail
set +x

KEYSTORE="$HOME/.config/ferry/ferry-release.jks"
ALIAS="ferry"
REPO="BPRVT/Ferry"
VALIDITY_DAYS=10950   # 30 years — an Android signing key must outlive the app

export PATH="$HOME/ferry-toolchain/bin:$PATH"
JAVA_HOME="${JAVA_HOME:-$HOME/ferry-toolchain/jdk/jdk-17.0.20+8/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

command -v gh      >/dev/null || { echo "error: gh not found" >&2; exit 1; }
command -v keytool >/dev/null || { echo "error: keytool not found" >&2; exit 1; }

if [ -f "$KEYSTORE" ]; then
    echo "A release keystore already exists at:"
    echo "  $KEYSTORE"
    echo
    echo "Refusing to overwrite it — that would permanently orphan every build"
    echo "already signed with it. Delete it by hand if you are certain."
    exit 1
fi

cat <<'BANNER'
=== Ferry release key ===

You are about to create the key that identifies every future Ferry build.
Back up the resulting file. If you lose it you can never ship an update that
existing installs will accept.

BANNER

# ── Password ────────────────────────────────────────────────────────────────
printf 'New keystore password (min 6 chars, will not echo): '
read -rs PW1
echo
if [ ${#PW1} -lt 6 ]; then
    echo "error: keytool requires at least 6 characters." >&2
    exit 1
fi
printf 'Confirm password: '
read -rs PW2
echo
if [ "$PW1" != "$PW2" ]; then
    echo "error: passwords did not match." >&2
    exit 1
fi

mkdir -p "$(dirname "$KEYSTORE")"
chmod 700 "$(dirname "$KEYSTORE")"

echo
echo "Generating 4096-bit RSA key…"
keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$PW1" \
    -keypass "$PW1" \
    -dname "CN=Ferry, OU=Ferry, O=Ferry, C=US" \
    >/dev/null

chmod 600 "$KEYSTORE"
echo "✓ created $KEYSTORE"

# Same password for store and key keeps the Gradle config simple; the store file
# is the thing that must stay secret, and it is protected either way.
echo
echo "Setting GitHub Actions secrets…"
base64 -i "$KEYSTORE" | gh secret set KEYSTORE_BASE64   --repo "$REPO"
printf '%s' "$PW1"     | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS"   | gh secret set KEY_ALIAS         --repo "$REPO"
printf '%s' "$PW1"     | gh secret set KEY_PASSWORD      --repo "$REPO"

unset PW1 PW2

echo
gh secret list --repo "$REPO"

cat <<BANNER

Done.

BACK THIS UP NOW — copy it somewhere safe and offline:
  $KEYSTORE

Store the password in your password manager. It is not recoverable, and it is
not written anywhere in this repository.
BANNER
