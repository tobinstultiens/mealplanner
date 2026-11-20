#!/usr/bin/env python3
"""
Reading and tapping the Android screen through `adb` and `uiautomator`, the logic behind
`tap.sh`, `texts.sh` and `shot.sh` (use those; see README.md in this folder).

Why a script and not a one-liner: a label such as "✓ Gegeten" next to a button "Gegeten" must not
be tapped for the button. So a pattern has to match the whole text (or content description), a
node that can be clicked beats one that cannot (a text inside a clickable parent taps the parent),
and more than one match is an error that lists the candidates, never a guess.

    screen.py texts                       # every node with text or description, bounds first
    screen.py tap 'Gegeten'               # whole text must match; the only clickable match is tapped
    screen.py tap --contains 'Gegeten'    # substring instead; --index N picks among several
    screen.py shot out.png                # screenshot, checked to be a real PNG
    screen.py --self-test                 # on made-up dumps and a fake adb, no emulator needed

`ADB` in the environment names the adb binary (default ~/Android/Sdk/platform-tools/adb), `SETTLE`
the seconds to wait after a tap before the screen is dumped again (default 1).
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PNG = b"\x89PNG\r\n\x1a\n"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class Node:
    def __init__(self, label, bounds, clickable):
        self.label, self.bounds, self.clickable = label, bounds, clickable

    @property
    def centre(self):
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2

    def __str__(self):
        return f"{list(self.bounds)} {self.label!r}{' clickable' if self.clickable else ''}"


def parse(dump: str):
    """The nodes of a uiautomator dump that say something, each with the bounds a tap should hit.
    A node that is not clickable itself takes the bounds of its nearest clickable ancestor,
    because that is what a tap on its text reaches."""
    start = dump.find("<hierarchy")
    end = dump.rfind("</hierarchy>")
    if start < 0 or end < 0:
        raise ValueError("no <hierarchy> in the dump: is the device connected and unlocked?")
    nodes = []

    def walk(element, clickable_ancestor):
        bounds = BOUNDS.fullmatch(element.get("bounds", ""))
        box = tuple(map(int, bounds.groups())) if bounds else None
        owner = (box, True) if element.get("clickable") == "true" and box else clickable_ancestor
        text, desc = element.get("text", ""), element.get("content-desc", "")
        target = owner or ((box, False) if box else None)
        if target:
            for label in dict.fromkeys(l for l in (text, desc) if l):
                nodes.append(Node(label, target[0], target[1]))
        for child in element:
            walk(child, owner)

    walk(ET.fromstring(dump[start:end + len("</hierarchy>")]), None)
    return nodes


def find(nodes, pattern, contains=False):
    """The nodes whose label matches, only the clickable ones when there are any; nodes with the
    same bounds count once (a button and its label are one thing to the user)."""
    match = re.search if contains else re.fullmatch
    hits = [n for n in nodes if match(pattern, n.label)]
    if any(n.clickable for n in hits):
        hits = [n for n in hits if n.clickable]
    seen, unique = set(), []
    for n in hits:
        if n.bounds not in seen:
            seen.add(n.bounds)
            unique.append(n)
    return unique


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, check=False)


def dump_screen() -> str:
    return adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout.decode("utf-8", "replace")


def listing(nodes) -> str:
    return "\n".join(str(n) for n in nodes)


def tap(pattern, contains=False, index=None) -> int:
    nodes = parse(dump_screen())
    hits = find(nodes, pattern, contains)
    if not hits:
        print(f"NOTFOUND {pattern!r}; on screen:\n{listing(nodes)}", file=sys.stderr)
        return 1
    if len(hits) > 1 and index is None:
        print(f"AMBIGUOUS {pattern!r}, use --index N:", file=sys.stderr)
        for i, n in enumerate(hits):
            print(f"  {i}: {n}", file=sys.stderr)
        return 2
    if (index or 0) >= len(hits):
        print(f"NOTFOUND index {index} of {len(hits)} matches of {pattern!r}", file=sys.stderr)
        return 1
    chosen = hits[index or 0]
    x, y = chosen.centre
    print(f"tap {chosen.label!r} at {x},{y}")
    adb("shell", "input", "tap", str(x), str(y))
    return 0


def shot(path) -> int:
    data = adb("exec-out", "screencap", "-p").stdout
    if not data.startswith(PNG):
        print("no PNG from screencap (device offline? screen locked?), nothing written", file=sys.stderr)
        return 1
    with open(path, "wb") as out:
        out.write(data)
    print(path)
    return 0


def show_screen_again() -> None:
    """Dump the screen after a tap: it has changed, so the next step must not be chosen from the
    one before."""
    time.sleep(float(os.environ.get("SETTLE", "1")))
    print("--- screen now ---")
    try:
        print(listing(parse(dump_screen())))
    except ValueError as problem:
        print(problem, file=sys.stderr)


# ---- self-test ----------------------------------------------------------------------------

SAMPLE = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
 <node text="" content-desc="" clickable="false" bounds="[0,0][1080,2400]">
  <node text="✓ Gegeten" content-desc="" clickable="false" bounds="[40,300][400,360]"/>
  <node text="" content-desc="" clickable="true" bounds="[600,280][1040,380]">
   <node text="Gegeten" content-desc="" clickable="false" bounds="[700,300][940,360]"/>
  </node>
  <node text="Plan" content-desc="" clickable="true" bounds="[0,2200][360,2400]"/>
  <node text="Shop" content-desc="Shopping list" clickable="true" bounds="[360,2200][720,2400]"/>
  <node text="Add" content-desc="" clickable="true" bounds="[100,500][300,600]"/>
  <node text="Add" content-desc="" clickable="true" bounds="[100,700][300,800]"/>
 </node>
</hierarchy>
UI hierarchy dumped to: /dev/tty"""

FAKE_ADB = """#!/bin/bash
echo "$@" >> "$FAKE_LOG"
case "$1 $2" in
  "exec-out uiautomator") cat "$FAKE_DUMP" ;;
  "exec-out screencap") cat "$FAKE_PNG" ;;
esac
"""


def self_test() -> int:
    global ADB
    nodes = parse(SAMPLE)
    count = 0

    def check(name, ok):
        nonlocal count
        count += 1
        if not ok:
            print(f"FAILED: {name}", file=sys.stderr)
            sys.exit(1)

    def bounds(hits):
        return [n.bounds for n in hits]

    hit = find(nodes, "Gegeten")
    check("the whole text matches, not the label '✓ Gegeten'", bounds(hit) == [(600, 280, 1040, 380)])
    check("a text inside a clickable parent taps the parent's centre", hit[0].centre == (820, 330))
    check("--contains still prefers the clickable one", bounds(find(nodes, "Gegeten", True)) == [(600, 280, 1040, 380)])
    check("a content description matches too", len(find(nodes, "Shopping list")) == 1)
    check("two equal buttons are two hits", len(find(nodes, "Add")) == 2)
    check("an unclickable-only match is still returned", len(find(nodes, "✓ Gegeten")) == 1)
    check("regex alternatives work", len(find(nodes, "Plan|Shop")) == 2)
    try:
        parse("Error: device offline")
        check("a dump without hierarchy is refused", False)
    except ValueError:
        check("a dump without hierarchy is refused", True)

    with tempfile.TemporaryDirectory() as tmp:
        fake = os.path.join(tmp, "adb")
        with open(fake, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(fake, 0o755)
        dump_file, png_file, log = (os.path.join(tmp, n) for n in ("dump.xml", "ok.png", "log"))
        with open(dump_file, "w") as f:
            f.write(SAMPLE)
        with open(png_file, "wb") as f:
            f.write(PNG + b"data")
        os.environ.update(FAKE_DUMP=dump_file, FAKE_PNG=png_file, FAKE_LOG=log)
        ADB = fake

        def taps():
            return [l for l in open(log).read().splitlines() if "input tap" in l] if os.path.exists(log) else []

        check("tap on a unique button succeeds", tap("Gegeten") == 0)
        check("and sends the tap to the button's centre", taps() == ["shell input tap 820 330"])
        check("an ambiguous pattern taps nothing and fails", tap("Add") == 2 and len(taps()) == 1)
        check("--index picks one", tap("Add", index=1) == 0 and taps()[-1] == "shell input tap 200 750")
        check("a missing label fails", tap("Nope") == 1 and len(taps()) == 2)
        check("an index past the matches fails", tap("Add", index=5) == 1 and len(taps()) == 2)
        out = os.path.join(tmp, "s.png")
        check("shot writes a PNG", shot(out) == 0 and open(out, "rb").read().startswith(PNG))
        with open(png_file, "wb") as f:
            f.write(b"error: device offline")
        bad = os.path.join(tmp, "bad.png")
        check("shot refuses non-PNG output and leaves no file", shot(bad) == 1 and not os.path.exists(bad))
    print(f"self-test ok ({count} checks)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("texts")
    t = sub.add_parser("tap")
    t.add_argument("pattern")
    t.add_argument("--contains", action="store_true")
    t.add_argument("--index", type=int)
    s = sub.add_parser("shot")
    s.add_argument("path")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        if args.command == "texts":
            print(listing(parse(dump_screen())))
            return 0
        if args.command == "tap":
            code = tap(args.pattern, args.contains, args.index)
            if code == 0:
                show_screen_again()
            return code
        if args.command == "shot":
            return shot(args.path)
    except ValueError as problem:
        print(problem, file=sys.stderr)
        return 1
    parser.print_usage()
    return 2


if __name__ == "__main__":
    sys.exit(main())
