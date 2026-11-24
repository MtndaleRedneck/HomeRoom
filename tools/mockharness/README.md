# Mock-HTTP contract harness (not part of the app build)

Runs the REAL reconstructed ApiClient (compiled for the JVM with stubs for its
Android surface) against a localhost fake server that validates method, path
and body keys per ../contract_golden.json and serves full-shape responses
whose sentinel values MockMain asserts after parsing (optString parsers
default silently, so bare no-exception passes would not prove the shape was
read). ../response_check.py (run by run.sh first) separately validates the
fake server against the declared response shapes in contract_golden.json's
"responses" section. Declared shapes come from the app's parse paths, NOT
from observed live-server traffic. This is the runtime companion to
../contract_check.py (which is source-level only).

## Stubs (harness-only, never shipped)
- SessionStub.kt: in-memory Session (7 members ApiClient touches)
- AvatarUploadStub.kt: AvatarUpload payload copy (app: Avatars.kt)
- UpdateCheckStub.kt: parseDigest only (app: UpdateCheck.kt is Android-coupled)

## Run
1. python3 mock_server.py 8471
2. kotlinc ApiClient.kt Models.kt + the stubs + MockMain.kt with okhttp/okio/
   coroutines/json/stdlib jars, then run app.homeroom.MockMainKt http://127.0.0.1:8471

Current status: 19/19 checks OK incl. sentinel response-shape assertions, plus
18/18 declared-shape validations from response_check.py, zero body/route
mismatches logged by the server (2026-11-24). rule-create is asserted to FAIL with capability_required
because the release gates it behind the global_owner_rules capability and the
mock session grants no features.

Scope: localhost only. No live account, no real server. Unverified routes
(SOURCE-GAPS.md) are deliberately NOT exercised here.
