#!/bin/bash
# Run a command with its clock moved: with-clock.sh <seconds|+Nd|+Nh|-Nd...> <command...>
#
#   scripts/emulator/with-clock.sh +3d python3 scripts/dev-oidc-provider.py &
#   scripts/emulator/with-clock.sh +3d java -jar server/build/libs/server-all.jar &
#
# The provider and the server must both run on the shifted clock, or the server rejects the
# provider's tokens as not yet valid or expired. The provider makes new signing keys at every
# start, so a restart logs the app out: start both once, shifted, and leave them running.
# The shim is built (needs gcc) into $CLOCK_SHIM_DIR, default ~/.cache/meal-clock-shim, a directory
# only you can write, and built again whenever clock-shim.c differs from the copy it was built from.
# `with-clock.sh --self-test` checks it on `date`.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dir=${CLOCK_SHIM_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/meal-clock-shim}
source=${CLOCK_SHIM_SOURCE:-$here/clock-shim.c}

seconds() {
  case $1 in
    *d) echo $(( ${1%d} * 86400 )) ;;
    *h) echo $(( ${1%h} * 3600 )) ;;
    *[!0-9+-]*|"") echo "not an offset: $1 (use seconds, 3d or -2h)" >&2; return 1 ;;
    *) echo $(( $1 )) ;;
  esac
}

# The shim is preloaded into other processes, so only a directory of ours that nobody else can write
# to is trusted. The source is copied next to the .so: a different source means build again.
build() {
  mkdir -p -m 700 "$dir"
  [ -O "$dir" ] && [ -z "$(find "$dir" -maxdepth 0 -perm /022)" ] \
    || { echo "$dir must belong to you and not be writable by others" >&2; return 1; }
  cp "$source" "$dir/clock-shim.c"
  gcc -shared -fPIC -O2 -o "$dir/clock-shim.so.new" "$dir/clock-shim.c" -ldl
  mv "$dir/clock-shim.so.new" "$dir/clock-shim.so"
}

up_to_date() { [ -f "$dir/clock-shim.so" ] && cmp -s "$source" "$dir/clock-shim.c"; }

if [ "${1:-}" = "--self-test" ]; then
  command -v gcc >/dev/null || { echo "self-test skipped: no gcc"; exit 0; }
  dir=$(mktemp -d); trap 'rm -rf "$dir"' EXIT
  source=$dir/source.c; cp "$here/clock-shim.c" "$source"; export CLOCK_SHIM_SOURCE=$source
  [ "$(seconds +3d)" = 259200 ] && [ "$(seconds -2h)" = -7200 ] && [ "$(seconds 90)" = 90 ] || { echo "FAILED: offsets"; exit 1; }
  if seconds soon 2>/dev/null; then echo "FAILED: bad offset accepted"; exit 1; fi
  CLOCK_SHIM_DIR=$dir "$0" +3d date +%s > "$dir/shifted"
  now=$(date +%s); shifted=$(cat "$dir/shifted")
  diff=$(( shifted - now ))
  [ "$diff" -ge 259199 ] && [ "$diff" -le 259205 ] || { echo "FAILED: date is $diff s ahead, expected 259200"; exit 1; }
  CLOCK_SHIM_DIR=$dir "$0" 0 date +%s > "$dir/same"
  diff=$(( $(cat "$dir/same") - $(date +%s) ))
  [ "$diff" -ge -5 ] && [ "$diff" -le 1 ] || { echo "FAILED: offset 0 moved the clock by $diff s"; exit 1; }
  # An unchanged source keeps the shim; a changed one builds it again.
  built=$(stat -c %i "$dir/clock-shim.so")
  CLOCK_SHIM_DIR=$dir "$0" 0 date >/dev/null
  [ "$(stat -c %i "$dir/clock-shim.so")" = "$built" ] || { echo "FAILED: shim rebuilt without a change"; exit 1; }
  echo "/* changed */" >> "$source"
  CLOCK_SHIM_DIR=$dir "$0" 0 date >/dev/null
  [ "$(stat -c %i "$dir/clock-shim.so")" != "$built" ] && cmp -s "$source" "$dir/clock-shim.c" || { echo "FAILED: shim not rebuilt after the source changed"; exit 1; }
  # A directory others can write to is refused.
  mkdir -m 777 "$dir/open"
  if CLOCK_SHIM_DIR=$dir/open "$0" 0 date >/dev/null 2>&1; then echo "FAILED: writable shim directory accepted"; exit 1; fi
  echo "self-test ok"
  exit 0
fi

[ $# -ge 2 ] || { echo "usage: with-clock.sh <offset> <command...>" >&2; exit 2; }
offset=$(seconds "$1"); shift
up_to_date || build
FAKE_OFFSET_SECONDS=$offset LD_PRELOAD="$dir/clock-shim.so${LD_PRELOAD:+:$LD_PRELOAD}" exec "$@"
