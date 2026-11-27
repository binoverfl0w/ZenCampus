#!/bin/bash
set -euo pipefail
case "$PLATFORM_NAME" in
    iphonesimulator) target=iosSimulatorArm64 ;;
    iphoneos) target=iosArm64 ;;
    *) echo "Unsupported platform: $PLATFORM_NAME" >&2; exit 1 ;;
esac
source_dir="$SRCROOT/../shared/build/kotlin-multiplatform-resources/aggregated-resources/$target/composeResources"
dest="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/compose-resources/composeResources"
mkdir -p "$dest"
ditto "$source_dir" "$dest"

# Set metadata on every Xcode build, including builds started directly in Xcode.
source "$SRCROOT/build-settings.sh"
plist="$TARGET_BUILD_DIR/$INFOPLIST_PATH"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $version" "$plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $version_code" "$plist"
if [[ "$CONFIGURATION" == Release ]]; then
    /usr/libexec/PlistBuddy -c 'Set :ZenUpdateRepository ' "$plist"
fi
