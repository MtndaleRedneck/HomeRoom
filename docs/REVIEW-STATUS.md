# HomeRoom 1.5 private12 reconstruction - review status

Companion to RECONSTRUCTION-NOTICE.md. Current verification state of this
branch; updated as evidence lands. Items marked PENDING are not yet proven.

## Reference artifact

- Release APK sha256: `f6b7e5890377582005618b91ca08d2eaa228fb3163a0bd8ee467ded9d21a0a4d`
- Signer cert sha256: `0063cd1617c138f6ab8a2b58420f0d72fbfeac6d8b176bc77463e7c8eca932e6`

## Proven

- **JVM harness (this branch, `tools/`):** response_check 18/18, MockMain
  sentinel 19/19, contract_check 35/35 verified (2 explicitly labeled
  unverified), last run at commit `4a58461`.
- **CI compile verification:** the tree's release Kotlin compiles and release
  resources process at the Android toolchain level (Gradle 8.9, JDK 17),
  repeated green across multiple runs.
- **Unsigned-release guard:** CI asserts `assembleRelease` FAILS when keystore
  material is absent, so this tree cannot silently produce an unsigned
  release APK that looks like a deliverable.

## Qualified evidence (not shipping proof)

- **Debug emulator smoke (API 34 emulator, TCG software emulation):** the
  DEBUG variant installs, launches, process stays alive, and dumpsys shows
  `app.homeroom/.MainActivity` top-resumed, focused and visible, with no
  FATAL EXCEPTION in logcat. Same sources/resources, but NOT the
  minified/signed release bytes. On-device install on a real phone remains
  required before any "works in app" claim.
  - PENDING: visual screenshot from the emulator run (capture step failed on
    one run; rerun in progress).

## Server-contract reconciliation (2026-11-24, operator excerpts)

Production 1.4 (the only running server) has NO upload routes and
file_upload=false; upload/resume/preferences surfaces in this tree are
held-1.5 decompile-grounded and UNVERIFIABLE against any running server
until the held-1.5 server contract is recovered. The I2 blocker fixes
(cookie scoping, resume completion gate) are correct against the
decompile-evidenced client behavior; they are not end-to-end proof.
Fixed against the production contract + private12 decompile: Origin /
Sec-Fetch headers on every request, rename/archive dedicated CAS routes,
root-relative avatar URL resolution.
NOT claimed: functional readiness from compile/parser/mock evidence.

## Deliberate divergences from release behavior (security repairs)

- **Connect handoff always confirms while not enrolled.** The release
  auto-connected a handoff whose origin matched the saved server URL. A
  failed or cancelled earlier attempt leaves that URL saved, so the match
  bypassed consent (I2 finding). Now every handoff in the not-enrolled state
  shows the "Connect to this server?" dialog with the verified destination
  host. Enrolled behavior is unchanged (toast).
- **Incoming session cookies are origin-scoped** (I2 finding): a foreign
  response can no longer overwrite the enrolled session with a same-named
  cookie. Outgoing scoping landed earlier; both directions are pinned in the
  mock harness.
- **filePreview carries the same Origin/Sec-Fetch headers** as every other
  direct call (I2 consistency finding).
- **Composer retry is immutable-payload** (grounded in the release's own
  copy, K0/T0: a retry sends "the exact same message ID and body, never an
  edited copy"): the message input locks at the first send attempt and the
  snapshot text is what a retry carries.

## Pending

- **Signed-release verification:** confirm the exact current tree produces a
  signed release APK with the original signer certificate. Lane: CI-built
  unsigned release artifact (pinned to a commit), signed locally with the
  vault-kept keystore via apksig, cert sha256 compared against the reference
  above. Not yet executed.
- **Real-device smoke:** emulator evidence only so far.

## Known gaps

See RECONSTRUCTION-NOTICE.md (provenance classes, placeholder
google-services.json, lost test fixtures) and docs/SOURCE-GAPS.md.
