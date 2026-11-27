#!/usr/bin/env bash
# Prints the release notes for one version from CHANGELOG.md: the lines under "## <version>" up to the next "## ".
# Fails when the section is missing or empty, so a release cannot go out without notes.
#   scripts/release_notes.sh 0.1.0
#   git tag -a v0.1.0 -F <(scripts/release_notes.sh 0.1.0)
set -euo pipefail
version="${1:?usage: scripts/release_notes.sh <version>}"
changelog="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)/CHANGELOG.md"
notes="$(awk -v heading="## $version" '
  $0 == heading { found = 1; next }
  found && /^## / { exit }
  found { print }
' "$changelog" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')"
[[ -n "$notes" ]] || { echo "CHANGELOG.md has no notes under \"## $version\"." >&2; exit 1; }
printf '%s\n' "$notes"
