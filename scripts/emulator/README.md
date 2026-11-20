# Helpers for testing the app on the emulator

Used by the `emulator-tester` and `qa` agents (`.claude/agents/`), and by anyone driving the
emulator by hand. They need `adb` (`ADB` names it, default `~/Android/Sdk/platform-tools/adb`),
Python 3 and, for the clock, gcc. Each has a self-test that needs no emulator.

| Script | What it does | Self-test |
| --- | --- | --- |
| `tap.sh <regex>` | Taps the clickable node whose whole text or content description matches, then prints the new screen. Several matches: lists them and fails (`--index N` picks one). `--contains` matches a substring. | `python3 scripts/emulator/screen.py --self-test` |
| `texts.sh` | Prints every node with a text or description: bounds, label, `clickable`. | (same) |
| `shot.sh <file.png>` | Screenshot, refuses to keep anything that is not a PNG. | (same) |
| `seed-recipes.py` | Logs in to the dev provider as a user and adds eight recipes with amounts and a made-up profile to a local server. | `python3 scripts/emulator/seed-recipes.py --self-test` |
| `with-clock.sh <offset> <command>` | Runs the command (server, dev provider) with its clock moved, e.g. `+3d`, `-2h`, seconds. | `scripts/emulator/with-clock.sh --self-test` |

How they are tested: `screen.py --self-test` parses made-up `uiautomator` dumps (including a label
`✓ Gegeten` beside a button `Gegeten`, a text inside a clickable parent, two equal buttons) and
runs `tap` and `shot` against a fake `adb` script that records its calls. `seed-recipes.py` runs
against a stub that answers as the provider and the server. `with-clock.sh` builds the shim and
checks that `date` is three days ahead. Nothing here touches a device or a real server in a test.

## Why `tap.sh` matches the way it does

The whole text must match: a label `✓ Gegeten` is not the button `Gegeten`. A text that sits
inside a clickable parent taps the parent. After a tap the screen has changed, so the script waits
`SETTLE` seconds (default 1) and dumps it again; choose the next step from that dump, not the one
before.

## Clock: running on another day

`with-clock.sh` is for what depends on the date (the week rollover, notification windows, "today"
on the Plan). The device clock can be moved with `adb root` and `date`, but the server's cannot;
run the server and the dev provider shifted instead:

```bash
scripts/emulator/with-clock.sh +3d python3 scripts/dev-oidc-provider.py &
scripts/emulator/with-clock.sh +3d java -jar server/build/libs/server-all.jar &   # env as in README
```

Both must share the clock, or tokens look expired or not yet valid. The provider makes new signing
keys at every start, so restarting it logs the app out: start both once and leave them running.
The shim moves only the real-time clock; it works for the JVM and Python, not static binaries.

## Seeding

`seed-recipes.py --user tester --server http://localhost:18081` after the server and provider are
up. It adds recipes, it does not replace them: run it once per database, on a copy.
