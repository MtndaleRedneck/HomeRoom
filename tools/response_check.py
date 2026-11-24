#!/usr/bin/env python3
"""Response-shape validator for the mock-HTTP contract harness.

Starts mock_server.py on a scratch port, replays every harness route with a
minimal valid request body (from the golden "body" keys), and checks that the
actual response contains every field declared in the golden "responses"
section. Catches drift between the declared parse-path shape and what the
fake server serves.

The declared shapes come from the app's parse paths (Models.kt / ApiClient.kt),
NOT from observed live-server traffic; they say "the parser reads these keys",
nothing more.

Usage: python3 tools/response_check.py   (exits 1 on any missing field)
"""
import json, re, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

HERE = Path(__file__).resolve().parent
GOLDEN = json.loads((HERE / "contract_golden.json").read_text())
SERVER = HERE / "mockharness" / "mock_server.py"
PORT = 8472
BASE = f"http://127.0.0.1:{PORT}/homeroom/v1"

# method + concrete path per harness-exercised route (path params filled in)
ROUTES = [
    ("GET",  "/owner/profile", "bootstrap/profile-get"),
    ("POST", "/owner/profile", "profile-set-name"),
    ("GET",  "/owner/docs/ai-guide", "ai-guide"),
    ("GET",  "/owner/rooms", "rooms-roster"),
    ("GET",  "/owner/activity", "activity"),
    ("GET",  "/owner/needs-you", "needs-you"),
    ("GET",  "/owner/search?q=goal", "search"),
    ("GET",  "/owner/rooms/room1/files?limit=50", "files-page"),
    ("GET",  "/owner/updates?wait=0&limit=100", "held-updates"),
    ("GET",  "/owner/events/ev1", "event-get"),
    ("POST", "/read-events", "read-events"),
    ("POST", "/owner/ais/ai_1/join-codes", "join-code-create"),
    ("POST", "/owner/ais/ai_1/join-codes/j1/revoke", "join-code-revoke"),
    ("POST", "/owner/ais/ai_1/join-codes/j1/report-used", "join-code-report-used"),
    ("POST", "/owner/feedback", "feedback"),
    ("POST", "/push/register", "push-register"),
    ("POST", "/owner/session/renew", "session-renew"),
    ("GET",  "/owner/rooms/room1/preferences", "room-preferences-get"),
]

GOLD = {e["name"]: e for e in GOLDEN["verified"]}
SHAPES = GOLDEN.get("responses", {})

def dummy_body(name):
    """Minimal body containing the golden-required keys."""
    entry = GOLD.get(name, {})
    body = {}
    for k in entry.get("body", []):
        if k in ("event_ids", "rooms", "members"):
            body[k] = ["x"]
        elif k == "avatar" or k == "image":
            body[k] = {"mime_type": "image/jpeg", "base64": "AA=="}
        else:
            body[k] = "x"
    return body

def present(obj, path):
    """True if dotted path (with [] marking array descent) exists and is not null."""
    cur = [obj]
    for part in path.split("."):
        arr = part.endswith("[]")
        key = part[:-2] if arr else part
        nxt = []
        for o in cur:
            if not isinstance(o, dict) or key not in o or o[key] is None:
                return False
            v = o[key]
            if arr:
                if not isinstance(v, list) or not v:
                    return False
                nxt.extend(v)
            else:
                nxt.append(v)
        cur = nxt
    return True

def main():
    proc = subprocess.Popen([sys.executable, str(SERVER), str(PORT)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1.0)
        fails = 0
        for method, path, name in ROUTES:
            body = dummy_body(name) if method != "GET" else None
            # Header contract per production 1.4 (owner_room.origin /
            # mutation_origin) and the private12 build (Z1/b.c):
            # Sec-Fetch-Site on every request, Origin on non-GET.
            headers = {"Content-Type": "application/json",
                       "Sec-Fetch-Site": "same-origin"}
            if method != "GET":
                # Origin is scheme://host[:port] only - never the API prefix.
                headers["Origin"] = f"http://127.0.0.1:{PORT}"
            req = urllib.request.Request(BASE + path, method=method,
                                         data=json.dumps(body).encode() if body is not None else None,
                                         headers=headers)
            try:
                with urllib.request.urlopen(req) as resp:
                    data = json.loads(resp.read())
            except urllib.error.HTTPError as e:
                print(f"FAIL {name}: HTTP {e.code} {e.read().decode()[:120]}")
                fails += 1
                continue
            missing = [p for p in SHAPES.get(name, []) if not present(data, p)]
            if missing:
                print(f"FAIL {name}: missing declared fields {missing}")
                fails += 1
            else:
                print(f"OK   {name} ({len(SHAPES.get(name, []))} declared fields)")
        print(f"response_check: {'PASS' if fails == 0 else f'{fails} FAILURES'}")
        return 1 if fails else 0
    finally:
        proc.terminate()

if __name__ == "__main__":
    sys.exit(main())
