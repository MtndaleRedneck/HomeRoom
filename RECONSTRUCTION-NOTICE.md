# HomeRoom 1.5 (private12) - RECONSTRUCTED SOURCE, NOT THE ORIGINAL TREE

This branch contains a **reconstruction** of the HomeRoom 1.5 private12 app
source. It is **not** the original source tree, carries **no prior signoff**,
and must not be treated as reviewed or release-ready. The original private
source was lost; the exact release APK survives and is the reference
artifact (sha256
`f6b7e5890377582005618b91ca08d2eaa228fb3163a0bd8ee467ded9d21a0a4d`, signer
cert sha256 `0063cd1617c138f6ab8a2b58420f0d72fbfeac6d8b176bc77463e7c8eca932e6`).

## Provenance classes

Every file in this tree carries exactly one of these origins:

1. **recovered-source** - files restored from saved copies of the earlier
   work, contents preserved as saved.
2. **reconstructed-code** - files written fresh to match the observable
   behavior of the exact release build. These carry a `RECONSTRUCTED`
   header comment. Currently: `AiKeysScreen.kt`, the `AiKey` model in
   `Models.kt`, the `approveAiKey`/`denyAiKey`/`sendFeedback`/`hasFeature`
   members in `ApiClient.kt`, `FeedbackScreen.kt`, and one settings row in
   `SettingsScreen.kt`. The release organized some of this UI differently;
   file layout there is reconstructed, so organization may differ from the
   original even where behavior matches.
3. **source-from-prior-public-version** - static assets copied verbatim
   from the public HomeRoom repository (commit `ad4a6da`, the 1.1 tree):
   launcher icons, `default_room.png`, six `drawable/` vectors, `LICENSE`,
   `docs/`. `ic_launcher_round.xml` is derived as a copy of the recovered
   `ic_launcher.xml`.

## Known gaps and caveats

- `FeedbackLinks.kt` / the Google-Form feedback URL: **absent from the
  release build** (dead-stripped or removed before private12 was built).
  Intentionally not reconstructed.
- `app/google-services.json`: a clearly-marked placeholder is included so
  the build runs; the real Firebase config is a secret and stays out of the
  tree. A build made with the placeholder cannot receive push notifications.
- Unit-test fixtures (`hr15-fixtures.json`) were lost; `V15ModelsTest.kt`
  references them. Tests will be regenerated or explicitly skipped; any
  test count will state exactly what was verified and will NOT be equated
  with the original 186+186.
- Some earlier in-progress edits could not all be matched with certainty.
  An app file hash manifest accompanies the final build, and a semantic
  comparison against the exact release APK, plus fresh tests and full
  independent reviewer signoff, gates any release consideration. Byte
  equality with the original build is not expected and is not claimed.
- The `ApiClient` feature-flag population site is still being verified
  against the exact release build and the read-only API. No defaults are
  invented and no new server behavior is introduced.
