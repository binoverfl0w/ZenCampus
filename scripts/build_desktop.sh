#!/usr/bin/env bash
set -euo pipefail
ZEN_PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ZEN_PROJECT_DIR"
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ "$(uname -s)" == Darwin ]] && ZEN_JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null); then
    export JAVA_HOME="$ZEN_JAVA_HOME"
  fi
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in "$HOME/.local/share/stackdown-jdk-21" /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21-openjdk-amd64; do
    if [[ -x "$candidate/bin/java" ]]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
./gradlew :desktopApp:createDistributable --no-daemon
if [[ "$(uname -s)" == Darwin ]]; then
  printf '%s\n' "$ZEN_PROJECT_DIR/desktopApp/build/compose/binaries/main/app/zencampus.app"
else
  printf '%s\n' "$ZEN_PROJECT_DIR/desktopApp/build/compose/binaries/main/app/zencampus/bin/zencampus"
fi
