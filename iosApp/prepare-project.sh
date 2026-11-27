#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
for configuration in Debug Release; do
    bundle_id=io.github.binoverfl0w.zencampus
    if [[ "$configuration" == Debug ]]; then bundle_id+=.dev; fi
    bundle_id="${ZENCAMPUS_IOS_BUNDLE_ID:-$bundle_id}"
    sed -e 's/$(PRODUCT_BUNDLE_IDENTIFIER)/'"$bundle_id"'/g' \
        -e 's/$(ZEN_WIDGET_GROUP)/group.'"$bundle_id"'/g' \
        SimulatorEntitlements.plist > "build/SimulatorEntitlements-$configuration.plist"
    sed 's/$(ZEN_WIDGET_GROUP)/group.'"$bundle_id"'/g' Widgets/Entitlements.plist \
        > "build/SimulatorAppGroup-$configuration.plist"
done
