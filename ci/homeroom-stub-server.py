#!/usr/bin/env python3
"""SYNTHETIC CI stub server for the HomeRoom debug-interaction emulator job.

NOT a mock of production behavior. Every payload is canned, marked synthetic,
and exists only so the debug build on the emulator can reach the restored
private12 wiring (Room tools menu, settings rows, decision review flow) with
all five server feature flags advertised. Request log (JSONL) lets the
workflow assert exactly which stub endpoints the app called. The debug-only
cleartext overlay confines this to the debug variant; release stays
usesCleartextTraffic=false.

Usage: homeroom-stub-server.py --port 18080 --log /path/stub-log.jsonl
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CARD_HASH = "a" * 64  # synthetic 64-hex digest placeholder, not a real hash

CARD = {
    "decision_id": "stub-decision",
    "room_id": "stub-room",
    "version": 1,
    "content_sha256": CARD_HASH,
    "title": "Synthetic stub decision",
    "proposal": "Stub proposal text for visual reachability only. Not a real decision.",
    "options": [
        {"option_id": "opt-a", "label": "Stub option A"},
        {"option_id": "opt-b", "label": "Stub option B"},
    ],
    "state": "open",
    "effect_class": "record_only",
    "actor": "stub",
    "context_event_ids": [],
    "decision_type": None,
    "action": None,
    "action_sha256": None,
    "approval": None,
}

ROOMS = {
    "rooms": [
        {
            "room_id": "stub-room",
            "display_name": "Stub Room",
            "members": [{"actor": "stub-owner", "display_name": "Stub Owner"}],
            "unread": 0,
        }
    ]
}

EVENTS = {
    "events": [
        {
            "event_id": "stub-ev-1",
            "seq": 1,
            "actor": {"actor": "stub-owner", "display_name": "Stub Owner"},
            "text": "Stub message for the debug-interaction job.",
            "created_at": "2026-11-24T12:00:00Z",
            "own": False,
        },
        {
            "event_id": "stub-ev-2",
            "seq": 2,
            "actor": {"actor": "stub-owner", "display_name": "Stub Owner"},
            "text": "Stub message carrying a synthetic decision link.",
            "created_at": "2026-11-24T12:01:00Z",
            "own": False,
            "decision_id": "stub-decision",
        },
    ],
    "server_time": "2026-11-24T12:02:00Z",
    "has_more": False,
}

# Finite goal response so the interaction pass exercises the pinned-goal
# page end-to-end against the stub (synthetic flow only). Shape mirrors the
# app parser in CollaborationScreens.RoomGoalScreen: "current" (with
# version/text) and "pending_proposals" are both required.
GOAL = {
    "current": {"version": 1, "text": "Stub room goal for the debug-interaction job."},
    "pending_proposals": [],
}

FEATURES = {
    # SYNTHETIC: all five flags on so every restored gated entry is reachable
    # in the harness. Says nothing about production server flags.
    "features": {
        "held_updates": True,
        "ask_all": True,
        "room_goals": True,
        "staged_uploads": True,
        "voice_notes": True,
        "synthetic_stub": True,
    }
}


def prepare_answer(body):
    option_id = (body or {}).get("option_id", "opt-a")
    option = next((o for o in CARD["options"] if o["option_id"] == option_id), CARD["options"][0])
    return {
        "state": "confirmation_required",
        "confirmation_id": "stub-confirmation",
        "expires_at": "2099-01-01T00:00:00Z",
        "confirmation": {
            "content_sha256": CARD_HASH,
            "proposal": {
                "title": CARD["title"],
                "proposal": CARD["proposal"],
                "effect_class": CARD["effect_class"],
                "options": CARD["options"],
            },
            "option": option,
        },
    }


def confirm_answer(body):
    return {
        "decision_id": "stub-decision",
        "option_id": (body or {}).get("option_id", "opt-a"),
        "proposal_sha256": CARD_HASH,
        "state": "answered",
        "synthetic_stub": True,
    }


class Handler(BaseHTTPRequestHandler):
    log_path = None
    lock = threading.Lock()

    def _record(self, body=None):
        entry = {"ts": time.time(), "method": self.command, "path": self.path}
        if body is not None:
            entry["body"] = body
        with Handler.lock:
            with open(Handler.log_path, "a") as f:
                f.write(json.dumps(entry) + "\n")

    def _send(self, payload, cookie=False):
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        if cookie:
            self.send_header("Set-Cookie", "__Host-homeroom=stub-cookie; Path=/; Secure; HttpOnly")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _route(self, body=None):
        p = self.path.split("?")[0]
        # The app prefixes API paths with /homeroom/v1 (observed in the first
        # interaction run's stub log); normalize before routing.
        if p.startswith("/homeroom/v1"):
            p = p[len("/homeroom/v1"):]
        if self.command == "GET" and p == "/owner/session":
            return self._send({"csrf": "stub-csrf", "session_id": "stub-session"}, cookie=True)
        if self.command == "GET" and p == "/owner/instance":
            return self._send(FEATURES)
        if self.command == "GET" and p == "/owner/rooms":
            return self._send(ROOMS)
        if self.command == "GET" and p == "/owner/rooms/stub-room/events":
            return self._send(EVENTS)
        if self.command == "GET" and p == "/owner/rooms/stub-room/goal":
            return self._send(GOAL)
        if self.command == "GET" and p == "/owner/decisions/stub-decision":
            return self._send(CARD)
        if self.command == "POST" and p == "/owner/decisions/stub-decision/prepare-answer":
            return self._send(prepare_answer(body))
        if self.command == "POST" and p == "/owner/decisions/stub-decision/confirm-answer":
            return self._send(confirm_answer(body))
        # Unknown launch-path endpoint: empty object, and the log records the miss.
        return self._send({"synthetic_stub": True, "unhandled_path": p})

    def do_GET(self):
        self._record()
        self._route()

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw.decode() or "{}")
        except Exception:
            body = {"_unparsed": True}
        self._record(body)
        self._route(body)

    def log_message(self, *args):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--log", required=True)
    args = ap.parse_args()
    Handler.log_path = args.log
    open(args.log, "a").close()
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
