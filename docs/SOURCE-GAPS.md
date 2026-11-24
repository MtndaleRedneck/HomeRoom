# HomeRoom 1.5 private12 reconstruction - source gaps and labeled inferences

Status: WIP reconstruction (commit 6e5ff27). Compiles and assembles with the
original signer; NOT a release candidate. Every item below must be settled by
reviewer evidence (exact-APK decompile) before any candidate claim, and the
unverified network surfaces must not run against live accounts as if tested.

## Verified against the exact private12 release build (sha256 f6b7e589...)

Routes, bodies and models cited per file from the dex2jar/CFR decompile
(K0/J0, K0/E0, K0/s3, K0/t3, K0/f1, K0/x4, K0/i3, K0/m3, K0/t1, K0/v4, K0/l3,
K0/S2, K0/p3/q3/r3, K0/f4, K0/h4). See code comments marked
"RECONSTRUCTED Nov 23, 2026" with the class reference.

## Production 1.4 server contract (operator read-only excerpts, 2026-11-24)

The DEPLOYED server (example.invalid -> loopback 8844, pinned 1.4 runtime)
advertises `file_upload=false` and its exhaustive router has NO upload
create/chunk/complete/delete routes and no /owner/rooms/<id>/preferences
route. Those reconstruction surfaces are decompile-grounded from private12,
i.e. they target the HELD 1.5 server contract, which has not been recovered.
They must not be presented as working against the production server, and
they stay in the tree only as approved-1.5 features awaiting that contract.

Grounded against the production source and FIXED in the reconstruction:
- Origin/Sec-Fetch headers: every request now sends Sec-Fetch-Site:
  same-origin (+ Sec-Fetch-Mode/Dest), non-GET also Origin =
  scheme://host[:port] (no path). Matches both the production 1.4
  owner_room.origin/mutation_origin checks (403 bad_origin) and the exact
  private12 helper (Z1/b.c). The mock server enforces the same contract.
- Room rename/archive: routed to the production-evidenced dedicated POST
  routes with exact CAS bodies ({display_name|archived, metadata_version}).
- Avatar URLs: production sends ROOT-relative urls
  (/homeroom/v1/owner/...); the client now resolves them against the server
  root (previously doubled the /homeroom/v1 prefix).

## Unverified - do not execute against a live server as if tested

1. Upload creation: POST /owner/rooms/<id>/uploads body
   {client_id, name, mime_type, byte_size, sha256, kind} comes from the V15Api
   layer; the exact create route string is not visible in the decompile
   (chunk PUT /owner/uploads/<id>/chunks/<offset> and DELETE
   /owner/uploads/<id> ARE string-verified). ABSENT from production 1.4
   (file_upload=false). NEEDS: held-1.5 server contract.
2. /owner/rooms/<id>/preferences GET/PUT (mute/pin): decompile-grounded,
   ABSENT from the production 1.4 router. NEEDS: held-1.5 contract.
3. postWithUploads body keys (client_created_at, upload_ids): held-1.5;
   production 1.4 /rooms/<id>/posts accepts exactly {client_id, text,
   thread_key, parent_id, mentions, asks_owner}. Also unresolved whether
   null-valued optional keys may be sent (1.4 rejects unknown keys;
   the reconstruction always includes thread_key/parent_id, JSONObject.NULL
   when unset). NEEDS: held-1.5 contract + release post-builder evidence.
4. Rule.decidedBy / decidedAt: fields exist in the release Rule model (v4
   toString); JSON key names (decided_by / decided_at) are inferred snake_case.
5. Avatar fetch path handling: relative vs absolute avatar URLs assumed both
   supported; release behavior not traced.

## Reconstruction-original (no release counterpart; labeled in code)

- WorkManager workers and WORK_TAG (the release has no WorkManager)
- Waiting-age helper
- Update-banner UI (release header views headerSearch/headerIcon/navFade absent)
- Session renew worker scheduling (release renewOnOpen behavior is evidence;
  the periodic scheduling is not)
- Image picker contract (GetContent) and SAF saveDocument

## Open evidence questions

- MainActivity letter-method mapping (partially traced)
- DecisionScreen generic prepare-flow vs release No=reject-answer divergence
  (noted; release semantics are implemented in V15Api)
- OwnerAiGuide ack display (model verified; UI surface minimal)
