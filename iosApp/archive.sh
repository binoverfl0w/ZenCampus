#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/build-settings.sh"
cd "$root/iosApp"
xcodegen generate
archive="$root/iosApp/build/ZenCampus.xcarchive"
args=()
if [[ -n "${ZENCAMPUS_IOS_TEAM_ID:-}" ]]; then
    : "${ZENCAMPUS_ASC_KEY_PATH:?Set ZENCAMPUS_ASC_KEY_PATH}"
    : "${ZENCAMPUS_ASC_KEY_ID:?Set ZENCAMPUS_ASC_KEY_ID}"
    : "${ZENCAMPUS_ASC_ISSUER_ID:?Set ZENCAMPUS_ASC_ISSUER_ID}"
    args=(-allowProvisioningUpdates -authenticationKeyPath "$ZENCAMPUS_ASC_KEY_PATH" -authenticationKeyID "$ZENCAMPUS_ASC_KEY_ID" -authenticationKeyIssuerID "$ZENCAMPUS_ASC_ISSUER_ID" "DEVELOPMENT_TEAM=$ZENCAMPUS_IOS_TEAM_ID" CODE_SIGN_STYLE=Automatic)
else
    [[ "${ZENCAMPUS_IOS_UPLOAD:-0}" != 1 ]] || { echo 'Upload requires signing credentials' >&2; exit 1; }
    args=(CODE_SIGNING_ALLOWED=NO)
fi
# Signing and export require an Apple developer account and have not yet been tested.
xcodebuild -project ZenCampus.xcodeproj -scheme ZenCampus -configuration Release -destination 'generic/platform=iOS' -archivePath "$archive" "${build_settings[@]}" ZEN_UPDATE_REPOSITORY= "ZEN_CRASH_DSN=${ZENCAMPUS_CRASH_DSN:-}" "${args[@]}" archive
# Release binaries are stripped; Sentry names crash frames from these debug symbols (organization token, as for Android).
if [[ -n "${SENTRY_AUTH_TOKEN:-}" ]]; then
    npx --yes @sentry/cli@3.8.2 debug-files upload --project zencampus --type dsym --log-level warn "$archive/dSYMs"
fi
if [[ -n "${ZENCAMPUS_IOS_TEAM_ID:-}" ]]; then
    options="$(mktemp)"
    trap 'rm -f "$options"' EXIT
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict/></plist>' > "$options"
    /usr/libexec/PlistBuddy -c 'Add :method string app-store-connect' -c "Add :teamID string $ZENCAMPUS_IOS_TEAM_ID" -c 'Add :signingStyle string automatic' "$options"
    xcodebuild -exportArchive -archivePath "$archive" -exportPath "$root/iosApp/build/export" -exportOptionsPlist "$options" -allowProvisioningUpdates -authenticationKeyPath "$ZENCAMPUS_ASC_KEY_PATH" -authenticationKeyID "$ZENCAMPUS_ASC_KEY_ID" -authenticationKeyIssuerID "$ZENCAMPUS_ASC_ISSUER_ID"
    if [[ "${ZENCAMPUS_IOS_UPLOAD:-0}" == 1 ]]; then
        /usr/libexec/PlistBuddy -c 'Add :destination string upload' "$options"
        xcodebuild -exportArchive -archivePath "$archive" -exportPath "$root/iosApp/build/upload" -exportOptionsPlist "$options" -allowProvisioningUpdates -authenticationKeyPath "$ZENCAMPUS_ASC_KEY_PATH" -authenticationKeyID "$ZENCAMPUS_ASC_KEY_ID" -authenticationKeyIssuerID "$ZENCAMPUS_ASC_ISSUER_ID"
    fi
fi
