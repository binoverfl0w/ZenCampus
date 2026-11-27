#!/bin/bash
# Sourced by both entry points to keep Gradle's versions authoritative.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
property() { sed -n "s/^$1=//p" "$root/gradle.properties" | head -1; }
version="$(property 'zencampus\.version')"
version_code="$(property 'zencampus\.versionCode')"
[[ "$version" =~ ^[0-9]+(\.[0-9]+){0,2}$ && "$version_code" =~ ^[0-9]+$ ]] || { echo 'Invalid iOS version or build number in gradle.properties' >&2; exit 1; }
build_settings=("MARKETING_VERSION=$version" "CURRENT_PROJECT_VERSION=$version_code")
if [[ -n "${ZENCAMPUS_IOS_BUNDLE_ID:-}" ]]; then build_settings+=("ZEN_HOST_BUNDLE_ID=$ZENCAMPUS_IOS_BUNDLE_ID" "ZEN_WIDGET_GROUP=group.$ZENCAMPUS_IOS_BUNDLE_ID"); fi
