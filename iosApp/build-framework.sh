#!/bin/bash
set -euo pipefail
cd "$SRCROOT/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
# A Release build optimises the whole Kotlin program while linking, inside Gradle's JVM, and needs more heap than
# gradle.properties gives everyday builds. Debug and simulator builds keep that setting.
heap=()
if [[ "${CONFIGURATION:-}" == Release ]]; then heap=("-Dorg.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8"); fi
./gradlew :shared:embedAndSignAppleFrameworkForXcode --no-daemon ${heap[@]+"${heap[@]}"}
