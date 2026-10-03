# W13.9 — Device recovery and instrumentation safety

This file records what is known about the loss of the family TV's provisioning, what state the device and the
repository are in now, and the policy that keeps an instrumentation run away from a device that someone is
using.

## A. What erased the app on the family TV

**Confirmed, from the run's own log.** The W13.8 instrumentation run wrote a Unified Test Platform log next to
its results. Its lines, quoted verbatim:

```
INFO: Configuring AndroidTestApkInstallerPlugin: apks_to_install {
  install_options {
  uninstall_after_test: true
  apks_to_install {
  install_options {
  uninstall_after_test: true
  INFO: Installing [...\app\build\outputs\apk\debug\app-debug.apk] on device 172.16.1.2:5555.
  INFO: Installing [...\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk] on device 172.16.1.2:5555.
  INFO: Uninstalling tv.safetubeforkids.app for device 172.16.1.2:5555.
  INFO: Uninstalling tv.safetubeforkids.app.test for device 172.16.1.2:5555.
  INFO: Execute tv.safetubeforkids.app.debug.CredentialTokenInstrumentedTest.debugSetPin_installsAKnownCredential: IGNORED
```

Artifact: `tv-app/app/build/outputs/androidTest-results/connected/debug/utp.0.log` (timestamped with the run,
`TEST-MIBOX4 - 12-_app-.xml` in the same directory reports 28 tests, 2 skipped, 0 failed).

**Conclusion.** The uninstall is the behaviour of the Gradle task, not of the tests. The connected-test task
installs the debug and androidTest APKs with `uninstall_after_test = true` and removes
`tv.safetubeforkids.app` — app data included — when the run ends. Both credential tests were *skipped* in that
run, so no test action can be blamed for it. Observed after the run: `pm list packages` had no
`safetubeforkids` entry, `dumpsys package` was empty for the package, and `/auth/state` reported
`configured:false`.

**Withdrawn.** The W13.7 attribution to the credential tests is wrong and was corrected in W13.8-B.

**Hypothesis, not confirmed.** Whether the APK-level uninstall also runs when the task fails, is cancelled, or
is invoked through `connectedAndroidTest` rather than `connectedDebugAndroidTest` was not tested: testing it
means running the task against a device, which is the thing this phase forbids and the guard in section D
refuses. Until it is tested on a disposable target, assume every connected-task invocation removes the app.

**Not the cause.** The e2e harness (`tv-app/scripts/tv-e2e.ps1`) installs with `adb install -r` and never
uninstalls; the debug broadcasts it sends are read-only; there is no `uninstallAll` or `adb uninstall` in the
harness or in CI.

## B. Current state of the family TV

Read-only, from the device, no instrumentation run and no credential-mutating broadcast:

| Check | Command | Result |
|---|---|---|
| Package installed | `adb -s 172.16.1.2:5555 shell pm list packages \| grep safetubeforkids` | `package:tv.safetubeforkids.app` |
| App running | `adb shell pidof tv.safetubeforkids.app` | `27893` |
| Server version | `GET /status` | `{"version":"0.10.0","serverRunning":true,"protocolVersion":1}` |
| Provisioning | `GET /auth/state` | `{"success":true,"configured":false,"recoveryCodePending":false}` |
| Screen presented | `uiautomator dump` | `Welcome to SafeTube` / `Set up SafeTube from your phone and create a Parent PIN.` / `Continue` |
| Screensaver | `settings get secure screensaver_enabled` | `1` |
| Stay awake | `settings get global stay_on_while_plugged_in` | `0` |

The app is installed and running, and it presents ordinary first-run setup rather than an error state. What was
erased with the app data is the provisioning: no Parent PIN, no Recovery Code, no approved sources, no
approved library.

## C. Recovering the provisioning is the owner's step, and only the owner's

The Parent PIN can be created in exactly one place: the TV's own setup flow, with the PIN typed on the TV. The
README describes it as *"welcome, connect a phone, create the Parent PIN, save the Recovery Code, ready"*, and
the strings the app itself renders are `Set up SafeTube from your phone and create a Parent PIN.` and `Open the
parent page on your phone to choose what your child can watch.` (source: `ui/screens/OnboardingScreen.kt`;
the observed screen in section B is the first of those).

Steps, on the TV and a phone on the same local network:

1. On the TV: select **Continue** on the welcome screen and follow the setup screens.
2. On the phone: open the address the TV shows, on the TV's own local page (the TV serves the dashboard
   itself — there is no account and no cloud service).
3. Create the **Parent PIN** (six digits, typed on the TV's keypad).
4. Save the **Recovery Code** the TV displays.
5. Add the five approved sources in the parent dashboard, on the phone, again from the TV's own page.

Nothing in this repository can do those steps, and no debugging shortcut substitutes for them.

**Debug credential intents are not the recovery path.** `DEBUG_SET_PIN` exists, and the W13.8 attempts to use
it to restore the PIN all reported `Broadcast completed: result=0` while `/auth/state` stayed
`configured:false`: the intent produced no effect on the device. Setting the PIN through a test-only intent
would also write a credential nobody chose, which is not a recovery. This phase therefore sent no
`DEBUG_SET_PIN` and no `DEBUG_RESET_PIN`, and none should be sent.

## D. Safe test-device policy

**The rule.** A connected-device instrumentation run needs a device that nobody depends on. The family TV is a
shared living-room device with a provisioned app on it, so it is not a test device: no `connected*` task, and
no `uninstall*` task, runs against it.

**The guard.** `tv-app/app/build.gradle.kts` now attaches a check to every task whose name starts with
`connected`, `device` or `uninstall`. `./gradlew :app:tasks --all` on this checkout lists the tasks that reach a device, and every destructive one among them is covered: `connectedAndroidTest`, `connectedCheck`, `connectedDebugAndroidTest`, `deviceAndroidTest`, `uninstallAll`, `uninstallDebug`, `uninstallDebugAndroidTest`, `uninstallRelease`. The `install*` tasks are deliberately left unguarded: installing is additive, and the e2e harness installs with `adb install -r` on purpose. Without the opt-in a guarded task fails the build before it does any of its work:

```
Refusing to run connectedDebugAndroidTest: it installs and then uninstalls tv.safetubeforkids.app on the
attached device, which erases the parent PIN and the approved library. Point it at a disposable emulator or a
spare device and opt in for that run only: $env:SAFETUBE_ALLOW_DEVICE_INSTRUMENTATION = '1'
```

Three properties make it a guard rather than a decoration, and all three are asserted without a device by
`tv-app/scripts/check-destructive-instrumentation.test.js` (step `Instrumentation safety check` in CI):

- it runs in `doFirst`, ahead of the task's own actions, so it cannot be a warning printed after the app is
  already gone;
- it throws, so the run stops rather than continuing;
- it reads the opt-in at execution time and names no device, address or serial. Device identity is not a
  safety property, and the guard must not pretend to recognize one.

**What the guard does not do.** It does not make the task non-destructive, and the opt-in is not protection
for app data: it is a gate on a human. Once the opt-in is set, the run erases the app it instruments exactly as
before. The only protection for data is choosing a disposable target.

**CI.** CI runs no connected-device task at all: the workflow runs `assembleDebug testDebugUnitTest
testReleaseUnitTest`, the static checks, and uploads the debug APK. It has no emulator, no device and no
`connected*` step, so no CI job can reach the family device or any other.

**Executed since (W13.10).** The refusal has now been observed at runtime: `./gradlew
:app:connectedDebugAndroidTest` fails with the message above, and the device it was pointed at is left
untouched — same installation timestamp, same running process, no instrumentation APK, no test-platform
output. See `docs/W13_10_GUARD_VERIFICATION.md`. The opted-in path, and what the task does to an app when it
does run, remain unverified: no disposable emulator can boot on this host and no spare device exists.

## E. What remains open

- The family TV is unprovisioned until the owner completes section C. Every provisioning check — five approved
  sources, parent authentication, approved playback, unapproved blocking — stays `BLOCKED` until then; no
  earlier run's results are substituted for it.
- The guard's refusal has been observed at runtime (W13.10), but never on a disposable target, and the
  opted-in path has not been executed anywhere (section D).
- Whether the uninstall also happens on a failed or cancelled run is untested (section A).
- Carried from earlier phases: DASH adaptive switching unverified (every approved source is progressive);
  automatic quality upgrade not demonstrated on the TV; caption cue rendering not pixel-verified;
  network-failure and process-death paths covered by unit and integration tests only.
