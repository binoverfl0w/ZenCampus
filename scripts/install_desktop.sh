#!/usr/bin/env bash
set -euo pipefail
ZEN_PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ZEN_BUNDLE_DIR="${1:-$ZEN_PROJECT_DIR/desktopApp/build/compose/binaries/main/app/zencampus}"
if [[ ! -x "$ZEN_BUNDLE_DIR/bin/zencampus" ]]; then "$ZEN_PROJECT_DIR/scripts/build_desktop.sh"; fi
ZEN_INSTALL_BASE="${XDG_DATA_HOME:-$HOME/.local/share}"
ZEN_INSTALL_DIR="$ZEN_INSTALL_BASE/zencampus-desktop"
ZEN_APPLICATIONS_DIR="$ZEN_INSTALL_BASE/applications"
ZEN_ICON_DIR="$ZEN_INSTALL_BASE/icons/hicolor/scalable/apps"
mkdir -p "$ZEN_INSTALL_BASE" "$ZEN_APPLICATIONS_DIR" "$ZEN_ICON_DIR"
ZEN_STAGE_DIR="$(mktemp -d "$ZEN_INSTALL_BASE/.zencampus-install-XXXXXXXX")"
trap 'rm -rf -- "$ZEN_STAGE_DIR"' EXIT
cp -a -- "$ZEN_BUNDLE_DIR/." "$ZEN_STAGE_DIR/"
# Keep the installed application separate from private account data in .../zencampus.
if [[ -d "$ZEN_INSTALL_DIR" ]]; then
  if [[ -e "$ZEN_INSTALL_DIR.previous" ]]; then rm -rf -- "$ZEN_INSTALL_DIR.previous"; fi
  mv -- "$ZEN_INSTALL_DIR" "$ZEN_INSTALL_DIR.previous"
fi
mv -- "$ZEN_STAGE_DIR" "$ZEN_INSTALL_DIR"
cp -- "$ZEN_PROJECT_DIR/desktopApp/icons/zencampus.svg" "$ZEN_ICON_DIR/zencampus.svg"
python3 - "$ZEN_INSTALL_DIR" "$ZEN_APPLICATIONS_DIR/zencampus.desktop" <<'PY'
import sys
from pathlib import Path
binary = str(Path(sys.argv[1]) / 'bin/zencampus')
# Desktop Entry quoting is not shell quoting. Escape its four reserved characters explicitly.
quoted = '"' + binary.replace('\\', '\\\\').replace('"', '\\"').replace('`', '\\`').replace('$', '\\$').replace('%', '%%') + '"'
Path(sys.argv[2]).write_text(f'''[Desktop Entry]
Type=Application
Version=1.0
Name=ZenCampus
Comment=A quiet campus companion for University of Augsburg
Exec={quoted}
Icon=zencampus
Terminal=false
Categories=Education;
StartupNotify=true
StartupWMClass=io-github-binoverfl0w-zencampus-desktop-MainKt
''')
PY
if command -v update-desktop-database >/dev/null; then update-desktop-database "$ZEN_APPLICATIONS_DIR"; fi
printf 'Installed ZenCampus: %s\n' "$ZEN_INSTALL_DIR/bin/zencampus"
