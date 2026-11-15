#!/usr/bin/env bash
# Prints the release notes for the commits after FROM up to TO: one "- " line per squash commit
# title (the " (#n)" suffix removed), newest first, only for commits that touch the app or the
# server. The app shows this text under "What's new" when it offers the update.
#
# Usage: scripts/release-notes.sh [FROM] [TO]
#   FROM  the previous release tag; empty or missing (the first release): every commit up to TO
#   TO    the released commit (default HEAD)
# Prints nothing when there is nothing to list; never fails for that.
set -euo pipefail

from="${1:-}"
to="${2:-HEAD}"

range="$to"
if [ -n "$from" ]; then
  range="$from..$to"
fi

git log "$range" --format='%s' -- composeApp androidApp shared server \
  | sed -E 's/ \(#[0-9]+\)$//; s/^/- /'
