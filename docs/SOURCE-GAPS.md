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

## Unverified - do not execute against a live server as if tested

1. Room rename: no /owner/rooms rename endpoint exists in the decompile (only
   /owner/ais/<id>/rename). OwnerToolsScreens routes rename through the
   preferences PUT as a labeled approximation. The release has a "Rename room"
   UI (f4); its write path is unlocated. NEEDS: reviewer to locate the exact
   call in the DEX or confirm the release behavior.
2. Upload creation: POST /owner/rooms/<id>/uploads body
   {client_id, name, mime_type, byte_size, sha256, kind} comes from the V15Api
   layer; the exact create route string is not visible in the decompile
   (chunk PUT /owner/uploads/<id>/chunks/<offset> and DELETE
   /owner/uploads/<id> ARE string-verified). NEEDS: evidence for create route.
3. applyOriginHeaders: header set on the verified-download path is a
   reconstruction (Accept/Origin/CSRF). Release header set unverified.
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
