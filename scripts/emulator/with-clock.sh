#!/bin/bash
# Run a command with its clock moved: with-clock.sh <seconds|+Nd|+Nh|-Nd...> <command...>
#
#   scripts/emulator/with-clock.sh +3d python3 scripts/dev-oidc-provider.py &
#   scripts/emulator/with-clock.sh +3d java -jar server/build/libs/server-all.jar &
#
# The provider and the server must both run on the shifted clock, or the server rejects the
# provider's tokens as not yet valid or expired. The provider makes new signing keys at every
# start, so a restart logs the app out: start both once, shifted, and leave them running.
# The shim is built (needs gcc) into $CLOCK_SHIM_DIR, default /tmp/meal-clock-shim-$USER.
# `with-clock.sh --self-test` checks it on `date`.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dir=${CLOCK_SHIM_DIR:-/tmp/meal-clock-shim-$(id -un)}

seconds() {
  case $1 in
    *d) echo $(( ${1%d} * 86400 )) ;;
    *h) echo $(( ${1%h} * 3600 )) ;;
    *[!0-9+-]*|"") echo "not an offset: $1 (use seconds, 3d or -2h)" >&2; return 1 ;;
    *) echo $(( $1 )) ;;
  esac
}

build() {
  mkdir -p "$dir"
  gcc -shared -fPIC -O2 -o "$dir/clock-shim.so" "$here/clock-shim.c" -ldl
}

if [ "${1:-}" = "--self-test" ]; then
  command -v gcc >/dev/null || { echo "self-test skipped: no gcc"; exit 0; }
  dir=$(mktemp -d); trap 'rm -rf "$dir"' EXIT
  [ "$(seconds +3d)" = 259200 ] && [ "$(seconds -2h)" = -7200 ] && [ "$(seconds 90)" = 90 ] || { echo "FAILED: offsets"; exit 1; }
  if seconds soon 2>/dev/null; then echo "FAILED: bad offset accepted"; exit 1; fi
  CLOCK_SHIM_DIR=$dir "$0" +3d date +%s > "$dir/shifted"
  now=$(date +%s); shifted=$(cat "$dir/shifted")
  diff=$(( shifted - now ))
  [ "$diff" -ge 259199 ] && [ "$diff" -le 259205 ] || { echo "FAILED: date is $diff s ahead, expected 259200"; exit 1; }
  CLOCK_SHIM_DIR=$dir "$0" 0 date +%s > "$dir/same"
  diff=$(( $(cat "$dir/same") - $(date +%s) ))
  [ "$diff" -ge -5 ] && [ "$diff" -le 1 ] || { echo "FAILED: offset 0 moved the clock by $diff s"; exit 1; }
  echo "self-test ok"
  exit 0
fi

[ $# -ge 2 ] || { echo "usage: with-clock.sh <offset> <command...>" >&2; exit 2; }
offset=$(seconds "$1"); shift
[ -f "$dir/clock-shim.so" ] || build
FAKE_OFFSET_SECONDS=$offset LD_PRELOAD="$dir/clock-shim.so${LD_PRELOAD:+:$LD_PRELOAD}" exec "$@"
