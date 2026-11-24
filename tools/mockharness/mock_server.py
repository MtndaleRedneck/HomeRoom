#!/usr/bin/env python3
"""Fake HomeRoom server for the mock-HTTP contract harness. Validates each
incoming request against tools/contract_golden.json (method + path pattern +
required body keys) and returns full-shape responses. Localhost only; never
talks to a real account.

Response shapes are NOT observed live-server traffic. They are built from the
app's own parse paths (Models.kt / ApiClient.kt, themselves reconstructed from
the exact private12 release decompile): every field a parser reads is present,
with distinctive sentinel values so MockMain can prove each one survived the
client's parsing. See the "responses" section of contract_golden.json and
tools/response_check.py for the declared-shape validation."""
import json, re, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

GOLDEN = json.load(open(__file__.rsplit('/', 1)[0] + '/../contract_golden.json'))

# Map request -> golden entry by method + regex path.
ROUTES = [
    ("GET",  r"^/homeroom/v1/owner/session$", "session"),
    ("GET",  r"^/homeroom/v1/owner/profile$", "bootstrap/profile-get"),
    ("POST", r"^/homeroom/v1/owner/profile$", "profile-set-name"),
    ("GET",  r"^/homeroom/v1/owner/docs/ai-guide$", "ai-guide"),
    ("GET",  r"^/homeroom/v1/owner/rooms$", "rooms-roster"),
    ("GET",  r"^/homeroom/v1/owner/activity$", "activity"),
    ("GET",  r"^/homeroom/v1/owner/needs-you$", "needs-you"),
    ("GET",  r"^/homeroom/v1/owner/search$", "search"),
    ("GET",  r"^/homeroom/v1/owner/rooms/[^/]+/files$", "files-page"),
    ("GET",  r"^/homeroom/v1/owner/updates$", "held-updates"),
    ("GET",  r"^/homeroom/v1/owner/events/[^/]+$", "event-get"),
    ("POST", r"^/homeroom/v1/read-events$", "read-events"),
    ("POST", r"^/homeroom/v1/owner/ais/[^/]+/join-codes$", "join-code-create"),
    ("POST", r"^/homeroom/v1/owner/ais/[^/]+/join-codes/[^/]+/revoke$", "join-code-revoke"),
    ("POST", r"^/homeroom/v1/owner/ais/[^/]+/join-codes/[^/]+/report-used$", "join-code-report-used"),
    ("POST", r"^/homeroom/v1/owner/rules$", "rule-create"),
    ("POST", r"^/homeroom/v1/owner/feedback$", "feedback"),
    ("POST", r"^/homeroom/v1/push/register$", "push-register"),
    ("POST", r"^/homeroom/v1/owner/session/renew$", "session-renew"),
    ("GET",  r"^/homeroom/v1/owner/rooms/[^/]+/preferences$", "room-preferences-get"),
]
GOLD = {e["name"]: e for e in GOLDEN["verified"]}

SHA_A = "a" * 64
SHA_B = "b" * 64

# Full-shape responses: every field the app's parser for that route reads,
# with sentinel values MockMain asserts on.
RESPONSES = {
    "session": {"csrf": "test-csrf", "owner_id": "owner1", "owner_display_name": "Test Owner", "session_id": "test-session", "features": {}},
    "profile-set-name": {"display_name": "Owner"},
    "bootstrap/profile-get": {"display_name": "Owner",
                              "avatar": {"url": "https://example.invalid/a.png", "sha256": SHA_B}},
    "ai-guide": {"version": 1, "sha256": SHA_A, "source": "server",
                 "updated_at": "2026-11-23T00:00:00Z", "text": "guide",
                 "acks": [{"actor": "ai_1", "version": 1, "at": "2026-11-23T00:00:00Z"}]},
    "rooms-roster": {
        "rooms": [{
            "room_id": "room1",
            "display_name": "The Den",
            "name": "ignored-name",
            "main_thread_key": "t1",
            "unread_count": 2,
            "muted": False,
            "waiting_count": 1,
            "pending_rules_count": 1,
            "members": [{"actor": "ai_1", "display_name": "Helper"},
                        {"actor": "homeroom-test", "display_name": "Synth"}],
            "waiting": [{"waiting_on": "ai_1", "event_id": "ev9",
                         "since": "2026-11-23T00:00:00Z", "notified_at": None,
                         "excerpt": "ping"}],
            "waiting_has_more": False,
            "waiting_cursor": None,
            "last_post": {"excerpt": "last words", "created_at": "2026-11-23T00:00:00Z", "seq": 41},
            "icon": {"emoji": "den"},
            "metadata_version": 3,
            "archived": False,
            "pinned": True,
        }],
        "server_time": "2026-11-23T00:00:00Z",
        "global_pending_rules_count": 1,
    },
    "activity": {"items": [{"kind": "note", "id": "a1"}], "next_cursor": "ac1"},
    "needs-you": {"items": [{"kind": "approval", "id": "n1"}]},
    "search": {"results": [{"event_id": "ev1", "room_id": "room1", "room_name": "The Den",
                            "excerpt": "goal met", "created_at": "2026-11-23T00:00:00Z",
                            "match_start": 3, "match_length": 4}],
               "next_cursor": "sc1"},
    "files-page": {"files": [{"file_id": "f1", "name": "notes.txt", "title": "Notes",
                              "mime_type": "text/plain", "byte_size": 12, "uploader": "ai_1",
                              "created_at": "2026-11-23T00:00:00Z", "kind": "file",
                              "url": "https://example.invalid/f1", "event_id": "ev1"}],
                   "has_more": True, "cursor": "fc1"},
    "held-updates": {"changes": [{"room_id": "room1", "seq": 42}], "next_cursor": "c1"},
    "event-get": {"event": {"event_id": "ev1", "seq": 41, "thread_key": "t1", "parent_id": None,
                            "actor": {"actor": "ai_1", "display_name": "Helper"},
                            "text": "hello", "created_at": "2026-11-23T00:00:00Z",
                            "client_id": "c9", "version": 2, "room_id": "room1"}},
    "join-code-create": {"join_id": "j1", "actor": "ai_1", "display_name": "AI",
                         "expires_at": "2026-11-24T00:00:00Z", "server_time": "2026-11-23T00:00:00Z",
                         "join_message": "join"},
    "room-preferences-get": {"version": 1, "pinned": False, "mute_mode": "off", "archived": False},
}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def handle_req(self):
        parsed = urlparse(self.path)
        path = parsed.path
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b""
        for method, pattern, name in ROUTES:
            if self.command == method and re.match(pattern, path):
                entry = GOLD.get(name, {})
                missing = []
                if entry.get("body"):
                    try:
                        data = json.loads(body or b"{}")
                    except Exception:
                        data = {}
                    missing = [k for k in entry["body"] if k not in json.dumps(data)]
                if missing:
                    self._reply(400, {"error": "missing_body_keys", "missing": missing})
                else:
                    self._reply(200, RESPONSES.get(name, {}))
                print(f"{self.command} {path} -> {name} {'BAD-BODY' if missing else 'ok'}", flush=True)
                return
        self._reply(404, {"error": "no_route"})
        print(f"{self.command} {path} -> NO ROUTE", flush=True)

    do_GET = handle_req
    do_POST = handle_req
    do_PUT = handle_req
    do_DELETE = handle_req

    def _reply(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8471
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
