#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/zencampus-uitests.XXXXXX")"
runtime="${ZEN_IOS_TEST_RUNTIME:-$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x.get("isAvailable") and x["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-") and "-iPhone" not in x["identifier"]]; print(r[-1]["identifier"] if r else "")')}"
device="${ZEN_IOS_TEST_DEVICE_TYPE:-$(xcrun simctl list devicetypes -j | python3 -c 'import json,sys; d=[x for x in json.load(sys.stdin)["devicetypes"] if x["name"].startswith("iPhone ") and x["name"].endswith(" Pro")]; print(d[-1]["identifier"] if d else "")')}"
[[ -n "$runtime" && -n "$device" ]] || { echo 'No suitable iOS simulator runtime or device type found.' >&2; exit 1; }
simulator="$(xcrun simctl create ZenCampus-UI-Validation "$device" "$runtime")"
# The simulator and the build products go when the run ends; the result bundle stays for inspection.
# ZEN_IOS_KEEP_SIMULATOR=1 keeps the simulator and its app data, for example to look into a failure.
cleanup() {
    if [[ "${ZEN_IOS_KEEP_SIMULATOR:-0}" == "1" ]]; then
        echo "Kept validation simulator $simulator"
    else
        xcrun simctl shutdown "$simulator" >/dev/null 2>&1 || true
        xcrun simctl delete "$simulator" >/dev/null 2>&1 || true
    fi
    rm -rf "$work/build" "$work/stub.app"
    echo "Results: $work/results.xcresult"
}
trap cleanup EXIT
echo "Validation simulator: $simulator; results: $work"
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b
# Put a harmless document in Files' local storage so the test exercises the OS handoff.
if files_storage="$(xcrun simctl get_app_container "$simulator" com.apple.DocumentsApp groups |
    awk -F '\t' '$1 == "group.com.apple.FileProvider.LocalStorage" {print $2}')" && [[ -n "$files_storage" ]]; then
    mkdir -p "$files_storage/File Provider Storage"
    printf 'Offline submission fixture for ZenCampus.\n' > "$files_storage/File Provider Storage/Sample submission.txt"
fi
cd "$root"
ZEN_IOS_UI_FIXTURE=1 ./iosApp/build-simulator.sh
xcodebuild build-for-testing -project iosApp/UITests/ZenUI.xcodeproj -scheme ZenUITarget \
    -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$work/build"
product="$work/build/Build/Products/Debug-iphonesimulator/ZenUITarget.app"
mv "$product" "$work/stub.app"
ditto iosApp/build/ZenCampus.app "$product"
xctestruns=("$work"/build/Build/Products/*.xctestrun)
[[ ${#xctestruns[@]} == 1 && -f "${xctestruns[0]}" ]] || { echo 'Expected one xctestrun file.' >&2; exit 1; }
xcodebuild test-without-building -xctestrun "${xctestruns[0]}" \
    -destination "platform=iOS Simulator,id=$simulator" -parallel-testing-enabled NO \
    -resultBundlePath "$work/results.xcresult"
