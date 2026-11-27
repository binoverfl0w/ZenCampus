#!/usr/bin/env bash
set -euo pipefail
ZEN_PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ZEN_DESKTOP_BIN="$ZEN_PROJECT_DIR/desktopApp/build/compose/binaries/main/app/zencampus/bin/zencampus"
if [[ ! -x "$ZEN_DESKTOP_BIN" ]]; then "$ZEN_PROJECT_DIR/scripts/build_desktop.sh"; fi
exec "$ZEN_DESKTOP_BIN" "$@"
