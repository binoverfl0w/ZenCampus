#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/build-settings.sh"
cd "$root/iosApp"
update_repo="${ZENCAMPUS_UPDATE_REPO:-$(property 'zencampus\.updateRepo')}"
[[ -z "$update_repo" || "$update_repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Invalid update repository' >&2; exit 1; }
build_settings+=("ZEN_UPDATE_REPOSITORY=$update_repo" "ZEN_CRASH_DSN=${ZENCAMPUS_CRASH_DSN:-}")
if [[ "${ZEN_IOS_UI_FIXTURE:-0}" == 1 ]]; then build_settings+=("SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG ZEN_UI_FIXTURE"); fi
xcodegen generate
xcodebuild -project ZenCampus.xcodeproj -scheme ZenCampus -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build/DerivedData ARCHS=arm64 CODE_SIGNING_ALLOWED=NO "${build_settings[@]}" build
rm -rf build/ZenCampus.app
ditto build/DerivedData/Build/Products/Debug-iphonesimulator/ZenCampus.app build/ZenCampus.app
# Keep the simulator keychain entitlement in the linked section only.
# Signing the simulated keychain group into the host prevents launch.
codesign --force --sign - --entitlements build/SimulatorAppGroup-Debug.plist build/ZenCampus.app/PlugIns/ZenCampusWidgets.appex
codesign --force --sign - --entitlements build/SimulatorAppGroup-Debug.plist build/ZenCampus.app
printf '%s\n' "$root/iosApp/build/ZenCampus.app"
