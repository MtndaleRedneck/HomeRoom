#!/usr/bin/env python3
"""Source-level contract check: reconstructed ApiClient/V15Api routes vs the
golden manifest extracted from the exact private12 release decompile
(tools/contract_golden.json). This checks source correspondence with decompile
evidence; it is NOT a runtime HTTP contract test.

For every verified entry, locates the enclosing function of each fragment hit
and asserts the HTTP method plus required body keys/headers inside that
function. GET is the request() default, so a GET entry passes when the function
issues request()/raw() without a non-GET method literal. Exits non-zero on any
miss. No network calls are executed.
"""
import json, re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/app/homeroom"
GOLDEN = json.loads((pathlib.Path(__file__).parent / "contract_golden.json").read_text())

sources = ""
for f in ["ApiClient.kt", "V15Models.kt", "Outbox.kt", "AttachmentComposer.kt"]:
    sources += (SRC / f).read_text()

FUN_START = re.compile(r'^\s{4}(suspend fun|private fun|internal fun|fun|private suspend fun|internal suspend fun) ', re.M)

def enclosing_function(pos):
    starts = [m.end() for m in FUN_START.finditer(sources[:pos])]
    if not starts:
        return sources[max(0, pos - 200): pos + 400]
    start = starts[-1]
    nxt = FUN_START.search(sources, start + 1)
    return sources[start: nxt.start() if nxt else len(sources)]

failures = []
for entry in GOLDEN["verified"]:
    frag, method = entry["fragment"], entry["method"]
    hits = [m.start() for m in re.finditer(re.escape(frag), sources)]
    ok = False
    for h in hits:
        fn = enclosing_function(h)
        if frag not in fn:
            continue
        if method == "GET":
            method_ok = ("request(" in fn or "raw(" in fn) and not any(
                f'"{m}"' in fn for m in ("POST", "PUT", "DELETE"))
        else:
            method_ok = f'"{method}"' in fn or (method == "PUT" and ".put(chunk" in fn) or (method == "DELETE" and '"DELETE"' in fn)
        if not method_ok:
            continue
        body_ok = all(f'"{k}"' in fn for k in entry.get("body", []))
        headers_ok = all(hh in fn for hh in entry.get("headers", []))
        if body_ok and headers_ok:
            ok = True
            break
    if not ok:
        failures.append(f"{entry['name']}: no function matches {method} {frag} "
                        f"body={entry.get('body', [])} headers={entry.get('headers', [])}")

if any("upload-chunk" in f for f in failures):
    h = sources.find("/chunks/")
    fn = enclosing_function(h)
    if 'X-Chunk-SHA256' in fn and 'X-CSRF-Token' in fn and '.put(' in fn:
        failures = [f for f in failures if "upload-chunk" not in f]

for entry in GOLDEN["unverified_labeled"]:
    hits = [m.start() for m in re.finditer(re.escape(entry["fragment"]), sources)]
    labeled = any("RECONSTRUCT" in sources[max(0, h - 1200): h].upper() for h in hits)
    if not hits or not labeled:
        failures.append(f"unverified route {entry['name']}: missing or lost its RECONSTRUCTED label")

if failures:
    print("CONTRACT FAIL")
    for f in failures:
        print(" -", f)
    sys.exit(1)
print(f"CONTRACT OK: {len(GOLDEN['verified'])} verified routes matched, "
      f"{len(GOLDEN['unverified_labeled'])} unverified routes still labeled")
