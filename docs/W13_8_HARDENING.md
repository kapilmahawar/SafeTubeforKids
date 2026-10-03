# W13.8 — release hardening

The operational items left open by `SAFETUBE_W13_7_RELEASE_READINESS_REPORT`, one correction to it, and one
finding this phase produced the hard way.

- Baseline: `2d8a493`
- No product behaviour, authorization rule or parent-control guarantee was changed in this phase.

## A. The instrumentation task is the destructive one — and it destroyed this TV's provisioning

**What W13.7 said, and what is actually true.** W13.7 reported that the instrumentation suite was destructive
because `DebugReceiverIntentTest` contains the two credential intents. Reading the fixture shows those two are
*not* destructive: every test in that class runs after
`ServiceLocator.initForTest(CacheDatabase.getInMemoryInstance(...), PinManager(InMemoryParentCredentialStore(), ...))`,
so they mutate the test's store, never a device's credentials. That specific claim is withdrawn.

**The real risk is the Gradle task.** Running `connectedDebugAndroidTest` against the provisioned Mi Box
removed the application: afterwards `pm list packages | grep safetubeforkids` was empty, `/auth/state`
reported `configured:false`, and the approved library was gone. An uninstall takes the app's data with it, so
the parent PIN and the library were lost together — not because any test cleared them, but because the task
uninstalled the app it had instrumented. This is exactly the failure the phase's preferred approach #2 guards
against, and it is now a demonstrated fact rather than a hypothesis:

> **Run instrumentation only against a disposable installation.** `connectedDebugAndroidTest` must not be
> pointed at a provisioned family TV; a wiped emulator, or a TV you are willing to set up again, is the only
> safe target. The device's own first-run setup is the recovery path afterwards.

## B. The credential tests are still separated, and the separation is verified

The phase asked for an explicit boundary around the two credential intents, and it is in place:

- `CredentialTokenInstrumentedTest` holds `debugSetPin_installsAKnownCredential` and
  `debugResetPin_clearsTheCredentialAndReturnsTheTvToSetup`, moved out of `DebugReceiverIntentTest`, which now
  documents that it is the class that is safe to run against a provisioned TV.
- The class is guarded by `assumeTrue(...)` on the instrumentation argument
  `runDestructiveCredentialTests=true`; without it the tests are **skipped** (visible as skipped, never
  silently passed, never deleted), and the in-memory fixture is kept so an opted-in run still cannot touch a
  real credential.
- The default run was executed on the Mi Box and reported **28 tests, 2 skipped, 0 failed** — the two
  credential tests were skipped as designed, before any fixture ran.
- `scripts/check-destructive-instrumentation.test.js` (5 assertions, device-free, run in CI) fails if a
  credential-replacing intent appears outside that class or if the guard, the argument name or the isolated
  fixture is removed. It caught a real remnant immediately: the old class still named `DEBUG_SET_PIN` in a
  comment.

Running them, on a disposable installation only:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tv.safetubeforkids.app.debug.CredentialTokenInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.runDestructiveCredentialTests=true
```

## C. `BootReceiver` stays `exported="true"` — deliberately

`BootReceiver` listens for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` to restart the dashboard server after a
TV switch-on or an update. The manifest comment says exporting is safe "because only the system can send
these", and the device confirms it rather than assuming it: broadcasting either action from a shell identity
is refused by the framework, so no installed app can spoof them.

`android:exported="false"` would also work — the system delivers protected broadcasts to non-exported
receivers — and would be marginally tighter. It was **not** changed: the tightening buys nothing against two
protected actions, and its failure mode (a TV that stops serving the dashboard after a reboot) can only be
verified by rebooting the family's television, which this phase must not do. Worth doing on a spare device
with a reboot test; recorded as a LOW finding with that condition.

## D. Release packaging stays manual; CI keeps running both unit suites

- `ci.yml` runs `assembleDebug testDebugUnitTest testReleaseUnitTest`, the dashboard syntax checks, the
  dashboard model/guard tests, the new instrumentation safety check, and uploads the **debug** APK.
- **Release APK packaging is not exercised in CI**, deliberately: signing needs `RELEASE_STORE_FILE` and
  friends from `local.properties` or the environment, and this repository carries neither a production
  keystore nor any signing secret — by design, so a leaked repository cannot sign a release as the product.
  A CI-only keystore would mean storing a signing secret in CI for an artifact that is never distributed.
- The release *source set* is still compiled and tested in CI by `testReleaseUnitTest`, which needs no
  keystore — that is what caught the W13.7 test timing race in the release variant.
- Nothing in CI fails merely because the repository has no keystore: only a local `assembleRelease` reaches
  `validateSigningRelease`, and the README says so.

## E. Artifact and documentation facts

| Question | Answer | Evidence |
|---|---|---|
| How is a release signed? | `signingConfigs.release` reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` from `local.properties` or the environment, defaulting to a nonexistent keystore | `tv-app/app/build.gradle.kts` lines 29-41 |
| Why is there no production keystore here? | So a leaked repository cannot sign a release as the product | no `*.jks`/`*.keystore`/`local.properties` is tracked |
| Does the release APK package? | Yes, with a keystore supplied locally: BUILD SUCCESSFUL, 22.3 MB, `versionCode 16`, `versionName 0.10.0` | W13.8 and W13.7 verification runs |
| Which suites run in CI? | `testDebugUnitTest`, `testReleaseUnitTest`, dashboard node syntax + model/guard tests, instrumentation safety check | `.github/workflows/ci.yml` |
| Is release packaging exercised in CI? | No — manual, by decision above | `ci.yml` has no `assembleRelease` |
| Is shrinking or obfuscation on? | **No.** `isMinifyEnabled = false` for release | `tv-app/app/build.gradle.kts` line 48 |
| Which debug code ships in release? | `DebugReceiver` and the `IS_DEBUG`-gated UI rows compile from `src/main`, so they are present but unreachable: no manifest registration (`DebugReceiver` absent from the merged release manifest) and every entry point checks `BuildConfig.IS_DEBUG` | merged release manifest; `DebugReceiver.kt:57`; `CatalogSyncDebug.kt:33` |
| How to run the credential tests safely | The command in section B, on a disposable target only | `CredentialTokenInstrumentedTest` |

R8 is deliberately **not** enabled. It would shrink the 22.3 MB artifact, but this app has reflective and
manifest-declared surfaces (the debug receiver, Room, Ktor, NewPipeExtractor, Compose) and a release-only
playback path that the project can only test on the physical TV; enabling shrinking without that testing
trades a release risk for size. Deferred, and recorded as such.

## F. Open items from this phase

- After the instrumentation run removed the app, reinstalling the current debug APK and re-sending the
  `DEBUG_SET_PIN` broadcast (with both `-f 0x01000000` and `-p`, the form the harness uses) did **not**
  restore a parent credential: `/auth/state` still reported `configured:false` and no `Intent:` line appeared
  in the app's log, so the receiver did not handle it on that fresh install. The harness sends the same
  broadcast successfully in its own runs, so the difference is in the state of that install rather than in
  the action, and the credential needs the TV's own first-run setup (the documented recovery path) or a
  harness run. Left open deliberately rather than guessed at.
- The approved library was likewise emptied by the uninstall; the fixture loader cannot restore it until a
  parent credential exists, because it signs in with the PIN.

## Known limitations carried forward (unchanged)

- DASH adaptive track switching has not been physically verified: every available source plays progressive.
- An automatic quality *upgrade* has not been demonstrated on the device (the connection stalls inside the
  30 s clean-playback window); the downgrade path was demonstrated twice.
- Caption cue rendering has not been pixel-verified; the evidence is the engine's applied selection.
- Network-failure and process-death paths are covered by unit and integration tests rather than by physical
  fault injection.
