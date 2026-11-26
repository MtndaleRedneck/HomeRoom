#!/usr/bin/env python3
"""Harness-only: print a SharedPreferences XML file as sorted key=type:value lines
(values are the synthetic seed values only). Used to compare state before and after
an in-place upgrade without comparing file timestamps or raw bytes."""
import sys
import xml.etree.ElementTree as ET

for path in sys.argv[1:]:
    root = ET.parse(path).getroot()
    for el in sorted(root, key=lambda e: e.get("name", "")):
        val = el.get("value") if el.get("value") is not None else (el.text or "")
        print(f"{path.rsplit('/', 1)[-1]}:{el.get('name')}={el.tag}:{val}")
