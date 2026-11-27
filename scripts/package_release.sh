#!/usr/bin/env bash
# Builds what a GitHub release carries, into build/release:
#   zencampus-<version>.apk                     the Android app, signed with the release key
#   zencampus-<version>-linux-x86_64.tar.gz     the Linux bundle (unpack and run zencampus/bin/zencampus)
#   SHA256SUMS                                  checksums the apps verify before installing an update
# Signing needs ZENCAMPUS_KEYSTORE, ZENCAMPUS_KEYSTORE_PASSWORD, ZENCAMPUS_KEY_ALIAS and ZENCAMPUS_KEY_PASSWORD.
# ZENCAMPUS_UPDATE_REPO (owner/name) and ZENCAMPUS_CRASH_DSN switch on updates and crash reports in the builds.
set -euo pipefail
ZEN_PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ZEN_PROJECT_DIR"
version="$(sed -n 's/^zencampus\.version=//p' gradle.properties)"
if [[ -z "$version" ]]; then echo "zencampus.version is missing from gradle.properties" >&2; exit 1; fi
if [[ -z "${ZENCAMPUS_KEYSTORE:-}" || ! -f "$ZENCAMPUS_KEYSTORE" ]]; then
  echo "Set ZENCAMPUS_KEYSTORE to the release keystore; an unsigned APK cannot be installed or updated." >&2; exit 1
fi
if [[ -z "${ZENCAMPUS_UPDATE_REPO:-}" ]]; then echo "Warning: ZENCAMPUS_UPDATE_REPO is not set; these builds will not look for updates." >&2; fi
out="$ZEN_PROJECT_DIR/build/release"
rm -rf "$out"; mkdir -p "$out"
./gradlew --no-daemon :androidApp:assembleRelease :desktopApp:createDistributable
cp androidApp/build/outputs/apk/release/androidApp-release.apk "$out/zencampus-$version.apk"
# ZENCAMPUS_BUILD_TOOLS picks the build-tools whose apksigner checks the signature; otherwise the newest installed one.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ -n "${ZENCAMPUS_BUILD_TOOLS:-}" ]]; then
  apksigner="$sdk/build-tools/$ZENCAMPUS_BUILD_TOOLS/apksigner"
  [[ -x "$apksigner" ]] || { echo "No apksigner in build-tools $ZENCAMPUS_BUILD_TOOLS" >&2; exit 1; }
else
  apksigner="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [[ -n "$apksigner" ]]; then
  certs="$("$apksigner" verify --print-certs "$out/zencampus-$version.apk")" || { echo "$apksigner could not verify the APK" >&2; exit 1; }
  grep -E "Signer #1 certificate (DN|SHA-256)" <<< "$certs" || { printf '%s\n' "$certs" >&2; echo "The APK has no signer" >&2; exit 1; }
fi
tar -C desktopApp/build/compose/binaries/main/app -czf "$out/zencampus-$version-linux-x86_64.tar.gz" zencampus
(cd "$out" && sha256sum zencampus-* > SHA256SUMS)
cat "$out/SHA256SUMS"
