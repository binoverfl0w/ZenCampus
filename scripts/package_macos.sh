#!/usr/bin/env bash
# Builds the macOS release files into build/release (on an Apple silicon Mac):
#   zencampus-<version>-macos-arm64.dmg   the installer: drag ZenCampus to Applications
#   zencampus-<version>-macos-arm64.zip   the same app, which installed copies download to update themselves
#
# Signing needs a "Developer ID Application" certificate in the keychain and ZENCAMPUS_MAC_SIGN_IDENTITY set to its name
# (with or without the "Developer ID Application: " prefix); ZENCAMPUS_MAC_KEYCHAIN names a keychain other than the default.
# Notarisation also needs an App Store Connect API key: ZENCAMPUS_NOTARY_KEY_PATH (the .p8 file), ZENCAMPUS_NOTARY_KEY_ID
# and ZENCAMPUS_NOTARY_ISSUER. Without signing the files are ad-hoc signed: they run after the student approves them in
# System Settings → Privacy & Security, and they never update themselves (they link to the release page instead), so the
# release workflow publishes only their dmg.
set -euo pipefail
ZEN_PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ZEN_PROJECT_DIR"
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || { echo "Build the macOS release on an Apple silicon Mac." >&2; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
version="$(sed -n 's/^zencampus\.version=//p' gradle.properties)"
[[ -n "$version" ]] || { echo "zencampus.version is missing from gradle.properties" >&2; exit 1; }
identity="${ZENCAMPUS_MAC_SIGN_IDENTITY:-}"
identity="${identity#Developer ID Application: }"
notarize=no
if [[ -n "${ZENCAMPUS_NOTARY_KEY_PATH:-}" || -n "${ZENCAMPUS_NOTARY_KEY_ID:-}" || -n "${ZENCAMPUS_NOTARY_ISSUER:-}" ]]; then
  [[ -f "${ZENCAMPUS_NOTARY_KEY_PATH:-}" && -n "${ZENCAMPUS_NOTARY_KEY_ID:-}" && -n "${ZENCAMPUS_NOTARY_ISSUER:-}" ]] ||
    { echo "Notarisation needs ZENCAMPUS_NOTARY_KEY_PATH, ZENCAMPUS_NOTARY_KEY_ID and ZENCAMPUS_NOTARY_ISSUER." >&2; exit 1; }
  [[ -n "$identity" ]] || { echo "Notarisation needs a signed app: set ZENCAMPUS_MAC_SIGN_IDENTITY." >&2; exit 1; }
  notarize=yes
fi
if [[ -z "${ZENCAMPUS_UPDATE_REPO:-}" ]]; then echo "Warning: ZENCAMPUS_UPDATE_REPO is not set; this build will not look for updates." >&2; fi

out="$ZEN_PROJECT_DIR/build/release"
mkdir -p "$out"
rm -f "$out"/zencampus-*-macos-*
name="zencampus-$version-macos-arm64"
work="$(mktemp -d "${TMPDIR:-/tmp}/zencampus-macos.XXXXXX")"
trap 'rm -rf "$work"' EXIT

./gradlew --no-daemon :desktopApp:createDistributable
app="$ZEN_PROJECT_DIR/desktopApp/build/compose/binaries/main/app/zencampus.app"
[[ -x "$app/Contents/MacOS/zencampus" ]] || { echo "The app bundle was not built at $app" >&2; exit 1; }

keychain=()
if [[ -n "${ZENCAMPUS_MAC_KEYCHAIN:-}" ]]; then keychain=(--keychain "$ZENCAMPUS_MAC_KEYCHAIN"); fi

notarize_file() {
  local file="$1" result="$work/notary.json"
  echo "Submitting $(basename "$file") for notarisation; this usually takes a few minutes."
  xcrun notarytool submit "$file" --key "$ZENCAMPUS_NOTARY_KEY_PATH" --key-id "$ZENCAMPUS_NOTARY_KEY_ID" \
    --issuer "$ZENCAMPUS_NOTARY_ISSUER" --wait --timeout 1h --output-format json > "$result" || true
  local status id
  status="$(plutil -extract status raw -o - "$result" 2>/dev/null || true)"
  if [[ "$status" != "Accepted" ]]; then
    id="$(plutil -extract id raw -o - "$result" 2>/dev/null || true)"
    echo "Notarisation of $(basename "$file") ended with status '${status:-unknown}'." >&2
    if [[ -n "$id" ]]; then
      xcrun notarytool log "$id" --key "$ZENCAMPUS_NOTARY_KEY_PATH" --key-id "$ZENCAMPUS_NOTARY_KEY_ID" \
        --issuer "$ZENCAMPUS_NOTARY_ISSUER" >&2 || true
    fi
    exit 1
  fi
}

if [[ -n "$identity" ]]; then
  codesign --verify --deep --strict "$app"
fi
if [[ "$notarize" == yes ]]; then
  ditto -c -k --sequesterRsrc --keepParent "$app" "$work/app.zip"
  notarize_file "$work/app.zip"
  xcrun stapler staple "$app"
fi

# The update archive, from the (stapled) app.
ditto -c -k --sequesterRsrc --keepParent "$app" "$out/$name.zip"

# The disk image: the app beside a link to /Applications.
mkdir -p "$work/dmg"
ditto "$app" "$work/dmg/ZenCampus.app"
ln -s /Applications "$work/dmg/Applications"
hdiutil create -volname "ZenCampus" -srcfolder "$work/dmg" -ov -format UDZO "$out/$name.dmg" >/dev/null
if [[ -n "$identity" ]]; then
  codesign --sign "Developer ID Application: $identity" --timestamp ${keychain[@]+"${keychain[@]}"} "$out/$name.dmg"
fi
if [[ "$notarize" == yes ]]; then
  notarize_file "$out/$name.dmg"
  xcrun stapler staple "$out/$name.dmg"
fi

echo
if [[ "$notarize" == yes ]]; then echo "Signed and notarised:"; spctl --assess --type execute "$app" && echo "Gatekeeper accepts the app."
elif [[ -n "$identity" ]]; then echo "Signed, not notarised (Gatekeeper will still warn):"
else echo "Ad-hoc signed (allowed once in Privacy & Security; no self-update):"; fi
codesign -dvv "$app" 2>&1 | grep -E '^(Authority|TeamIdentifier)=' | head -3 || true
(cd "$out" && shasum -a 256 "$name.dmg" "$name.zip")
