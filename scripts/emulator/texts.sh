#!/bin/bash
# Print every node on the screen that has a text or description: bounds, label, "clickable".
exec python3 -I "$(dirname "$0")/screen.py" texts
