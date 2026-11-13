#!/usr/bin/env bash
# Backs up the Android release signing key to Bitwarden as the secure note
# "mealplanner-android-signing": the keystore base64-encoded in the notes, the password and alias
# as custom fields. Asks for the master password unless BW_SESSION is already set.
#
# Restore:
#   bw get notes mealplanner-android-signing | base64 -d > ~/.android/mealplanner-release.jks
# and put the mealplanner.* lines back in ~/.gradle/gradle.properties (password: the "password" field).
set -euo pipefail

name=mealplanner-android-signing
keystore="$HOME/.android/mealplanner-release.jks"
props="$HOME/.gradle/gradle.properties"

[ -f "$keystore" ] || { echo "No keystore at $keystore" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$props" | tail -n1; }
password="$(prop mealplanner.keystorePassword)"
alias="$(prop mealplanner.keyAlias)"
[ -n "$password" ] && [ -n "$alias" ] || { echo "Signing properties missing in $props" >&2; exit 1; }

if [ -z "${BW_SESSION:-}" ]; then
    # The master password prompt needs a real terminal (not Claude Code's `!`, which has none).
    if [ ! -t 0 ]; then
        echo "Run this in a terminal: bw needs to ask for the master password." >&2
        exit 1
    fi
    BW_SESSION="$(bw unlock --raw)" || true
    [ -n "$BW_SESSION" ] || { echo "Unlocking Bitwarden failed." >&2; exit 1; }
    export BW_SESSION
fi
# From here on bw must never prompt: a prompt would read the piped item data as keystrokes.
bw() { command bw --nointeraction "$@"; }
status="$(bw status | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])')"
[ "$status" = unlocked ] || { echo "Bitwarden is $status, not unlocked." >&2; exit 1; }
bw sync >/dev/null

existing="$(bw list items --search "$name" | python3 -c "import sys,json; print(sum(i['name']=='$name' for i in json.load(sys.stdin)))")"
if [ "$existing" != 0 ]; then
    echo "A Bitwarden item '$name' already exists; not creating a second one." >&2
    exit 1
fi

KEYSTORE_B64="$(base64 -w0 "$keystore")" PASSWORD="$password" ALIAS="$alias" NAME="$name" python3 -c '
import json, os
print(json.dumps({
    "type": 2,
    "secureNote": {"type": 0},
    "name": os.environ["NAME"],
    "notes": os.environ["KEYSTORE_B64"],
    "fields": [
        {"name": "password", "value": os.environ["PASSWORD"], "type": 1},
        {"name": "alias", "value": os.environ["ALIAS"], "type": 0},
        {"name": "restore", "value": "bw get notes mealplanner-android-signing | base64 -d > ~/.android/mealplanner-release.jks", "type": 0},
    ],
}))' | bw encode | bw create item >/dev/null

# Read it back and compare, so a truncated or mangled copy is caught now rather than when it is needed.
bw sync >/dev/null
if [ "$(bw get notes "$name" | base64 -d | sha256sum)" = "$(sha256sum < "$keystore")" ]; then
    echo "Stored and verified: Bitwarden item '$name'."
else
    echo "The copy in Bitwarden does not match the keystore; check the item by hand." >&2
    exit 1
fi
