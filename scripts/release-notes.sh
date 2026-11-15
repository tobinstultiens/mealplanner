#!/usr/bin/env bash
# Prints the release notes for the commits after FROM up to TO: one "- " line per squash commit
# title (the " (#n)" suffixes removed, also an issue reference before the pull request number),
# newest first. A commit is listed only when it changes app or server code: a file under
# composeApp/, androidApp/, shared/ or server/ that is not in a test source folder (src/*Test*/,
# src/test/, which includes the reference pictures there). A commit that changes both stays.
# The app shows this text under "What's new" when it offers the update.
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

git log "$range" --format='%x01%s' --name-only -- composeApp androidApp shared server \
  | awk '
      function flush() { if (title != "" && code) print "- " title }
      /^\x01/ { flush(); title = substr($0, 2); sub(/( \(#[0-9]+\))+$/, "", title); code = 0; next }
      NF && $0 !~ /(^|\/)src\/([^\/]*Test[^\/]*|test)\// { code = 1 }
      END { flush() }
    '
