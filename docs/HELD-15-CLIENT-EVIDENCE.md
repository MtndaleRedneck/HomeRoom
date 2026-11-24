# Held-1.5 server surface: client-side evidence table

PROVENANCE: every entry below is extracted from the private12 release APK
(HomeRoom-1.5-private12, sha256 f6b7e589...a0a4d) by dex2jar + CFR decompile
of obfuscated release classes. This is CLIENT evidence: proof of what the
release client called and with what JSON shape. It is NOT proof the server
implements these routes. Deployed production is 1.4 and lacks most of this
surface; a held-1.5 server plan/contract is being recovered separately by the
operator (read-only). Until that recovery, these routes are UNVERIFIABLE
against any running server and are tracked as parity gaps, not closed items.

Decompile anchors: K0/N5.java, K0/E0.java, K0/d1.java, K0/X0.java, K0/n1.java,
K0/p1.java, K0/q5.java, K0/t5.java, K0/C0.java, K0/v5.java, K0/n3.java,
K0/J0.java (base route list).

## Routes only in the held-1.5 surface (absent from production 1.4)

### Uploads (attachment pipeline)
- POST /owner/uploads (create): request shape seen in K0/E0 create path:
  name, size, sha256, voice flag (verified earlier against reconstruction).
- PUT /owner/uploads/<upload_id>/chunks/<index> (K0/E0 lines 104-120):
  raw body, content type application/octet-stream, header X-Chunk-SHA256,
  X-CSRF-Token on the mutation, Origin/Sec-Fetch-* via shared header helper
  Z1/b.c. Sequential, requires server offset == sent offset.
- POST /owner/uploads/<upload_id>/complete (K0/d1: "/uploads" + "/complete";
  X0: "/owner/uploads/"): body { client_id } (release string id complete_client).
  On failure the release shows "Upload not confirmed. Cancel and check the
  room before starting again." (d1). Reconstruction matches: same route,
  same body, same recovery copy, plus the added completion gate.
- Cleanup on local cancel (K0/X0): "Stopped locally. Server cleanup not
  confirmed; unused uploads expire." — release relied on server-side expiry
  for unused uploads; no explicit delete route evidenced.

### Decisions / ask-all (approval cards)
- POST /owner/rooms/<room_id>/ask-all (K0/N5.a):
  { client_id, client_created_at, text, actors: [], upload_ids: [] }.
  upload_ids present here in the RELEASE itself.

INFERENCE LABEL: no decompiled builder for POST /rooms/<id>/posts WITH
upload_ids has been found in the release; the release's plain-post builder
(K0/J0) sends only client_id/text/thread_key/parent_id. The release client's
actual attachment-post body remains UNEVIDENCED client-side.

OWNING-SOURCE SETTLED (2026-11-24, exact wrapper reconciliation sections
A-I, historical Nov 11 app code, NOT deployed): the conversation14 dispatch
sends any post body carrying client_created_at through outgoing15.post,
which VALIDATES the timestamp (older than 30d -> 410 outgoing_expired with
retry_new_id=false/requires_user_resend=true; more than 300s in the future
-> 400 future_created_at), hashes the ORIGINAL body (timestamp included)
for idempotency, strips client_created_at, namespaces client_id (o15_),
then reenters conversation14.post; a body with upload_ids continues into
files15.post whose exact inner allowlist is {client_id, text, thread_key,
parent_id, mentions, asks_owner, upload_ids} (fu_ namespacing, upload state
must be ready and unexpired, blobs consumed into the event). The OUTER wire
contract the client speaks therefore INCLUDES client_created_at; the
reconstruction sends it (a brief inner-allowlist-only removal was reverted
against this exact evidence). The wrapper records owner outgoing IDs for
the ambiguous-send recovery lookup (stale lookups 404 outgoing_not_found
with resend_same_id_only=true; conflicting reuse 409 client_id_conflict).
upload_ids: max 4, unique strings; empty text allowed when uploads attach.
The original release client's actual posts body stays unevidenced
client-side; the reconstruction targets this outer server contract.
- POST /owner/decisions/<id>/prepare-answer (N5 line ~257):
  { client_id, version, content_sha256, option_id: "yes" }.
- POST /owner/decisions/<id>/confirm-answer (N5.b):
  { client_id, confirmation_id, content_sha256, action_sha256,
    confirmed: true }.
- POST /owner/decisions/<id>/reject-answer (N5.l):
  { client_id, version, content_sha256 }.
- Decision edit/delete routes exist at string level (N5: "/edit", "/delete");
  edit body { client_id, version, text } (N5 line 231).
- Approval object build (N5 lines 183-204): { approval, receipt|snapshot,
  consumer_readiness, consumer_access? } — matches the approval_page deep-link
  extra documented as a gap; decision-card models are K0/E1, K0/L1.

### Events / reactions
- POST /owner/events/<event_id>/reaction (N5 line ~265):
  { client_id, reaction }.

### Exports
- POST /owner/exports (N5 line 239): { client_id, format: "bundle",
  include_attachments: true }.
- DELETE /owner/exports/<export_id> (K0/n1 line 64).
- GET /owner/exports/<export_id>/download (K0/p1 line 182-185).
- Export states handled by client: "ready_with_omissions" renders as
  "incomplete bundle", otherwise "complete bundle" (p1 line 188).
- Cancel copy: "Stopped checking. Server cancellation not confirmed; private
  export expires." (n1) — again client relies on server-side expiry.

### Owner status / team / test alerts
- GET /owner/status (K0/q5). K0/t5 ties a status_test_alert preference to it.
- POST /owner/status/test-alerts/ (K0/C0) — test alert delivery; pairs with
  the homeroom_test_alert deep-link extra (MainActivity.J) documented as a gap.
- GET /owner/team (K0/v5; failure copy "Couldn't load team state.").

### Room goal
- GET /owner/rooms/<room_id>/goal (K0/n3 lines 74-85, via J0.N owner GET).
- Goal proposal/approval UI exists (K0/a0 "Propose room goal", K0/o1
  "Make this goal live?" / "Reject this goal?"); write routes not yet
  decompiled to exact paths — string-level evidence only.

## Base route list present in release K0/J0 (shared with 1.4, already reconciled)

/approve, /avatar, /edit, /events?after=, /events?limit=,
/export?format=json&after=, /files?limit=, /history, /homeroom/v1,
/join-codes, /join-codes/, /members, /mode (plus rooms/, threads/, /read,
rename, archive, /owner/updates?wait=, /owner/rooms?include_archived=1 —
reconciled against production 1.4 contract in docs/SOURCE-GAPS.md).

## Owning-source reconciliation (2026-11-24, operator recovery)

PROVENANCE: recovered historical held-1.5 server modules (files15,
preferences15, askall15; cp38 snapshots with a cp42 crash-recovery patch)
are historical application evidence, NOT production, NOT a complete tree.
cp69 parity-audit observations are AUDIT PROSE, not supplied implementation;
items sourced only from that audit are marked [cp69-audit] and stay
unverified against code.

Matches confirmed against the modules (no client change needed):
- Upload create POST /owner/rooms/<room>/uploads, exact body {client_id,
  name, mime_type, byte_size, sha256, kind} - identical in reconstruction.
- Chunk PUT /owner/uploads/<id>/chunks/<offset>, application/octet-stream +
  X-Chunk-SHA256 + X-CSRF - identical. Server 415s other content types.
- Complete POST /owner/uploads/<id>/complete {client_id} - identical.
- Cancel DELETE /owner/uploads/<id> (no client_id - matches [cp69-audit]
  note that not every mutation carries one).
- Preferences GET/PUT /owner/rooms/<room>/preferences: PUT body is built
  fresh in the client with exactly {client_id, version, pinned, mute_mode,
  muted_until, archived} plus metadata_version only when the shared archive
  state changes - never a GET echo (room_id/archive_scope are response-only
  and would 400 under the exact allowlist). mute_mode off/until/forever;
  muted_until ISO-Z bounded to 366 days server-side. Preference version is
  distinct from metadata_version; conflicts are 409 preferences_version_
  conflict / room_version_conflict.
- Filename policy: server rejects names over 180 or with control chars
  rather than sanitizing; the client pre-cleans to the same bound.
- Ask-all create exact body {client_id, client_created_at, text, actors,
  upload_ids}; actors 1-20 unique; text <=8000; client_created_at within
  [-30d, +300s] else 410 outgoing_expired (requires user resend) / 400.
  Nonempty upload_ids: REFUSED in the cp38 module (409 feature_unavailable),
  claimed supported later [cp69-audit]. The reconstruction has no ask-all
  caller at all (always-empty upload_ids), so this is absent client-side
  support, not a live mismatch with the older server module.

Repairs made from this evidence (this branch):
- posts-with-uploads: client_created_at RESTORED per the exact outgoing15
  wrapper contract (sections A/B); retry keeps the whole original body
  byte-identical (locked payload + preserved identities), matching the
  wrapper's original-body idempotency hash. 410 outgoing_expired rotates
  the identity and requires an explicit user resend, never a silent swap.
- Complete replay SETTLED (sections E/G + cp70 patch I): same complete
  client_id after ready/consumed returns the immutable recorded status
  (replayed read) - the reconstruction's stable completeClient plus the
  requireComplete gate consumes exactly this. Different-ID complete is 409
  already_completed WITH the current public status (cp70; supersedes the
  cp69 audit note that said status was missing).
- Ask-all attachments: the ACTUAL cp68->69 askall15 patch (section H)
  supersedes the early nonempty-upload rejection: upload_ids <=4 unique,
  posted through the ordinary attachment path in the same transaction.
  Client-side ask-all remains absent (no caller), so nothing to reconcile
  beyond the doc.
- Status recent_test_alerts: actual cp69->70 patch (section I) implements
  the summary; the earlier 'unimplemented at checkpoint' note is superseded.
- Preferences: settled against the exact later source (sections C/D) -
  exact allowlist PUT, metadata_version only when the shared archive state
  changes, muted_until must be JSON null for off/forever. The roomMutation
  mute path now builds exactly this; the dedicated screen already did.
- Crash recovery: chunk/complete 409 upload_bytes_inconsistent (cp42:
  requires_restart, retry_same_upload=false, recovery_action=
  cancel_and_start_new_upload) now abandons the staged upload - record and
  staged file removed, Send stays locked - with the release's own copy
  (K0/e1). The client never auto-resumes after it. SCOPE LABEL: resume does
  not read GET upload status or restart fields; outside the explicit 409 it
  fails closed with cancel/reattach - it is not server-state reconciliation.
- Preferences PUT (room-list mute path): no longer merges the GET response -
  response-only keys (room_id, archive_scope) and stale muted_until on
  off/forever would 400 under the exact allowlist. The dedicated preferences
  screen already built exact bodies and is unchanged. No global-rule
  edit/retire caller exists in this tree.

Open points preserved, NOT closed:
- Team outer shape is {actors, server_time} [cp69-audit]; not re-verified.
- Export create is 202 queued / exact-retry 200, failures async
  (export_too_large with limit_bytes/requires_user_subset) [cp69-audit];
  reconstruction has no export UI (parity gap).
- Status recent-test summary unimplemented at checkpoint [cp69-audit];
  native provider acceptance is not device delivery.
- Production remains 1.4; none of this surface is live-verifiable.

## Reconstruction status of this surface

- Implemented in reconstruction, labeled held-1.5: chunk upload pipeline
  (UploadRepository/AttachmentComposer/StagedUpload), uploads/<id>/complete,
  Origin/Sec-Fetch-*/X-CSRF on all requests.
- NOT implemented (explicit parity gaps): ask-all composer, decision-card
  actions (prepare/confirm/reject/edit/delete), event reactions, exports UI,
  owner team view, room goal view/propose/approve, status test alerts.
  These are recorded in docs/SOURCE-GAPS.md and must not be treated as closed.
- Server-side truth for ALL of the above awaits the operator's held-1.5
  contract recovery. Do not ship claims about end-to-end upload/decision/export
  behavior from this client evidence alone.
