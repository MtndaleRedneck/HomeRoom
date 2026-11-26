#!/usr/bin/env python3
"""Print the pixel height of the first uiautomator node whose text starts with the given prefix (exit 1 if none)."""
import re, sys, xml.etree.ElementTree as ET
dump, prefix = sys.argv[1], sys.argv[2]
for n in ET.parse(dump).iter("node"):
    if n.get("text", "").startswith(prefix):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            print(int(m.group(4)) - int(m.group(2)))
            sys.exit(0)
sys.exit(1)
