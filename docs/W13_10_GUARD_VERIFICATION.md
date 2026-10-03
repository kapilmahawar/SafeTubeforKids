# W13.10 — Guard verification

W13.9 put a refusal in front of every device task and verified it with static checks only
(`docs/W13_9_RECOVERY_AND_TEST_SAFETY.md`, section D). This file records what has now been observed at
runtime, what remains unverified, and why.

## A. The refusal, observed at runtime

The verification below was run against the family Mi Box **after** the disposable-target route was exhausted
(section C) and **only** in the direction that cannot change the device: the task was invoked with the opt-in
absent, so the guard is expected to stop it before it does anything. No opted-in run — the one that installs
and then uninstalls the app — was performed on the family device. Before the first run the device reported
`/auth/state` = `{"success":true,"configured":false,"recoveryCodePending":false}`: setup is still incomplete,
so there is no provisioned credential or library on it to lose. The owner completed setup on the TV *after*
these runs: the device reported `configured:false` while they were made and `configured:true` when it was
checked again later, so the sentence above describes the device at the time of the runs, not now.

```
$env:SAFETUBE_ALLOW_DEVICE_INSTRUMENTATION   # absent
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Result: `gradle exit: 1`

```
* What went wrong:
Execution failed for task ':app:connectedDebugAndroidTest'.
> Refusing to run connectedDebugAndroidTest: it installs and then uninstalls tv.safetubeforkids.app on the attached
```

What the device looked like before and after that run, with the Mi Box attached and selected:

| Observation | Before | After |
|---|---|---|
| `pm list packages \| grep safetubeforkids` | `package:tv.safetubeforkids.app` | identical |
| `pm list packages \| grep safetubeforkids.app.test` | *(empty)* | identical |
| `pidof tv.safetubeforkids.app` | `27893` | `27893` |
| `dumpsys package … firstInstallTime` | `2026-10-04 02:42:57` | `2026-10-04 02:42:57` |
| `dumpsys package … lastUpdateTime` | `2026-10-04 02:42:57` | `2026-10-04 02:42:57` |
| UTP result files under `androidTest-results/` | 41 | 41 |

Every value is identical, so the run installed nothing, uninstalled nothing and never reached the Unified Test
Platform: an unchanged `firstInstallTime` is only possible if the package was never removed, and no new UTP
output means no test session started. The instrumentation APK was never present at any point.

## B. The opt-in is exact and per-run

The accepted value is the string `1` and nothing else. Both other values were tried with the device attached,
and both refused with the same message and `exit: 1`:

| `SAFETUBE_ALLOW_DEVICE_INSTRUMENTATION` | Result |
|---|---|
| absent | refused |
| `true` | refused |
| `0` | refused |
| `1` | not run against the family device (see section C) |

The value is read by `System.getenv` while the task runs, so it is a property of that process, not of the
project. It is not written to `gradle.properties`, `local.properties`, the workflow, or any script, and a
device-free check now fails if it ever appears in the project properties or in CI.

## C. Why there was no disposable target

An AVD exists on this host — `safetube_tv31`, `android-tv`, API 31, `x86`, `hw.device.name=tv_1080p` — and it
cannot start:

```
ERROR | x86 emulation currently requires hardware acceleration!
CPU acceleration status: Android Emulator requires an Intel/AMD processor with virtualization extension
support.  (Virtualization extension is not supported)
```

The host exposes no virtualization extension, so a bounded retry with `-accel off` cannot help an x86 image
either, and no other system image is installed. No spare physical Android device is available. The family Mi
Box is the only reachable target, which is why Part B of this phase stops here.

Consequently, and explicitly **not** inferred from anything observed:

- `GUARD_RUNTIME_WITH_OPT_IN`: unverified. The opted-in path has never been executed anywhere.
- Uninstall behaviour after the task runs at all — success, failure or cancellation — remains unverified from
  W13.9's log evidence alone (that log is quoted in `docs/W13_9_RECOVERY_AND_TEST_SAFETY.md`, section A).
- The refusal's position ahead of installation was demonstrated here by the device's own unchanged state; on a
  *disposable* target, with the run allowed to proceed, it has still never been watched.

## D. Coverage of the entry points

`./gradlew :app:tasks --all` lists every task that reaches a device, and each destructive one is guarded:
`connectedAndroidTest`, `connectedCheck`, `connectedDebugAndroidTest`, `deviceAndroidTest`, `uninstallAll`,
`uninstallDebug`, `uninstallDebugAndroidTest`, `uninstallRelease`. `install*` is deliberately left unguarded,
because installing is additive and the e2e harness depends on it.

The task graphs (`--dry-run`, which executes nothing) show that the aggregate entry points are covered
transitively and that no dependency does device work before the guarded task:

- `:app:connectedDebugAndroidTest` — dependencies are build tasks only (compile, dex, package, validate
  signing), then the guarded task itself;
- `:app:connectedAndroidTest` → `:app:connectedDebugAndroidTest`, both guarded;
- `:app:connectedCheck` → `:app:connectedAndroidTest` → `:app:connectedDebugAndroidTest`, all guarded;
- `:app:deviceAndroidTest` — no dependencies, guarded itself;
- `:app:uninstallAll` → `uninstallDebug`, `uninstallDebugAndroidTest`, `uninstallRelease`, each guarded, and
  the aggregate guarded too.

A different task name therefore cannot reach a device unguarded: there is no device-touching task that is not
in this set. The one remaining route is not a Gradle task at all — `adb shell am instrument` by hand — which no
build file can intercept and which this repository's process forbids.

## E. CI

CI never sets the opt-in and never invokes a device task; it runs `assembleDebug testDebugUnitTest
testReleaseUnitTest`, the static checks, and uploads the debug APK. Three device-free checks now hold that in
place, in `tv-app/scripts/check-destructive-instrumentation.test.js`: no `gradlew` line in the workflow
mentions a connected, device or uninstall task; the opt-in appears nowhere in it; and it holds no
`RELEASE_STORE_*`/`RELEASE_KEY_*` credential, consumes no `secrets.`, and builds no signed artifact.

## F. Open items

- No runtime verification of the opted-in path, and none of the uninstall behaviour on success, failure or
  cancellation, until a disposable target exists (a host with virtualization, or a spare device).
- The owner completed setup on the TV after the runs in this file. The checks that need the parent's
  credentials — parent authentication, the five approved sources, approved playback, unapproved blocking, and
  whether a probe source or bandwidth override is left behind — cannot be observed from outside the parent
  session, so they belong to the owner or to a run the owner asks for. No attempt was made to authenticate on
  the owner's behalf, and no fixture or probe content was inserted into the newly configured library.
