#!/bin/bash
# Tap the clickable thing whose whole text or description matches a regex, then print the new screen.
#   tap.sh 'Gegeten'  |  tap.sh --contains 'Gegeten'  |  tap.sh --index 1 'Add'
# Exit 1: nothing matches, 2: several match (the candidates are listed). See README.md.
exec python3 -I "$(dirname "$0")/screen.py" tap "$@"
