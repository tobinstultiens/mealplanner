#!/bin/bash
# shot.sh <file.png>: screenshot the device; fails (and writes nothing) when it is not a PNG.
exec python3 -I "$(dirname "$0")/screen.py" shot "$@"
