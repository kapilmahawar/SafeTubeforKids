# W12.2 — W6 harness cleanup and final stability

* Baseline the phase was issued against: `b0c7fca80e0ae0d9156212cd236c1d1f51cf9279` (W12.1).
* Actual starting HEAD: **`37b2d0dfe0baa17d9d822a3fc8f53dccad27c2a7`** — one commit past it: `37b2d0d` is the
  W12.1 correction that recorded the end-of-video finding after the full-tier run finished (docs only,
  no code). Reported rather than silently accepted.
* Device: Mi Box 4, Android 12. Worktree clean at start; no production source changed in this phase.
* Stable tags unchanged: `W10-STABLE-2026-09-28` → `421acc93a3fb34ab15b0cf98b0e3b4f32def8d3c`,
  `W10.1-STABLE-2026-09-28` → `eeb367a798854892059b521e500c6dcdeccad209`.

```text
W12_2_BASELINE=b0c7fca80e0ae0d9156212cd236c1d1f51cf9279
STARTING_HEAD=37b2d0dfe0baa17d9d822a3fc8f53dccad27c2a7
FINAL_HEAD=dfd4ae7  (see the commits section)
```

## 1. The instrumented PIN test — root cause proven by isolation

W12.1 classified `DebugReceiverIntentTest.debugSetPin_installsAKnownCredential` as shared-process
leakage from the persisted PIN attempt limiter. **That classification was wrong, and isolation says so:**
run alone with

```text
connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=
    tv.safetubeforkids.app.debug.DebugReceiverIntentTest#debugSetPin_installsAKnownCredential
```

it is `tests=1 failures=1`. So it is not contamination by other tests.

The real cause is the test's own fixture, not any shared state and not the product:

```kotlin
// the test built this
PinManager(store = InMemoryParentCredentialStore())            // no onPinValidated

// and PinManager.validate answers with a session or not at all:
is PinCheckResult.Verified -> {
    val issueSession = onPinValidated ?: return PinResult.NotConfigured    // <-- always taken here
```

A manager with no session issuer returns `NotConfigured` for **every** correct PIN, so
`assertTrue(validate("135790") is PinResult.Success)` could never pass - while the assertion above it
(`isConfigured()`) passed for the credential the fixture had set up itself. The limiter itself keeps no
persisted state here at all: `PinManager(pinLockoutPersistence = null)` gives each instance a fresh
limiter, and the in-memory store's `clear()` does clear the PIN, so the receiver's replace path
(`clearAll()` then `setup(pin)`) works exactly as written.

Fixed in the fixture, as the app wires it and the sibling auth suites already did: the test's
`PinManager` now takes `onPinValidated = { sessions.createSession() ?: "" }`, the same `SessionManager`
goes to `initForTest`, and the assertion names the result it saw instead of failing bare.

```text
PIN_TEST_ISOLATED=FAIL before the fix (and that is what disproves the leak theory)
PIN_TEST_FULL_SUITE=FAIL before the fix
PIN_TEST_ROOT_CAUSE=TEST_SETUP_FAILURE - the fixture omits the session issuer, so validate() cannot
                    return Success for any correct PIN
connectedDebugAndroidTest after the fix: 28 tests, 0 failures
```

## 2. The three W6 harness defects

### W6-B `w6-container-opens-its-children` — fixed

It compared the container's first child against the raw UI text with `[regex]::Escape` + `-match`, and the
children's titles carry emoji that the logcat/UI path cannot carry intact. It now uses the tolerant
match (`Test-W11Title`) for both the container's name and its first child, and it still requires both to
appear and still requires this to be the container screen rather than the home screen - so opening the
wrong container still fails. Normalisation is stated in the code: characters outside printable ASCII are
dropped and runs of whitespace collapsed, the same rule the example tier already used for the same
reason. It also logs the child node ids the app reports, so a future failure can be compared by identity.

### W6-A `w6-cards-are-focusable` — fixed

It required every card of a row to be in a single UI dump. A dump contains only what is *rendered*, and a
row is not obliged to fit on screen at once. It now walks the row: enter it, note the focused card, press
RIGHT, and require focus to land on another card the app says that row holds (single-card rows pass, with
the reason in the message). Failures log the focused label and the focusable labels.

### W6-C `w6-dpad-reaches-the-first-card` — fixed in form, still failing in practice

The old form pressed DOWN a fixed six times and read one dump, which scrolls the library *past* the row
being looked for - the row is then no longer rendered and the check reports `focused ''`. It now parks
focus at the top of the library (`Park-W11Focus`) and walks row by row with the focused label checked
after every press, using the same helper the example tier uses to reach all six of its rows.

**It still fails**, and the new diagnostics say exactly how:

```text
W6 focus diagnostics: foreground=tv.safetubeforkids.app lastKey=KEYCODE_DPAD_DOWN
W6 focus diagnostics: focused='Lights Out! JJ Isn't Scared of the Dark ... | ... | Animals for Kids'
W6 focus diagnostics: focusable=[Refresh | Connect Phone | Settings | FULL EPISODES ... | Bluey |
    This is the Way Bedroom! ... | Peppa Pig Best Videos | Lights Out! ...]
W6 focus diagnostics: expected card 'CoComelon Animal Songs' of 2 in 'CoComelon'
```

The walk ends at the **bottom** of the library (the One Video shelf is focused), and by then the
CoComelon row is scrolled out of the hierarchy - but it passed the CoComelon row on the way down without
matching it. The next step is therefore not another press budget: it is to navigate by the **projection's
row index** (DOWN × (index + 1) from a parked top, verified after each press) rather than by title - the
approach the example tier uses for its own rows, which is why that walk reaches 6/6. That is a change to
the navigation strategy, not to a sleep or a bound, and it is left undone rather than guessed at.

**The other two W6 checks are unverified in a full-tier run**: the player run that carried the W6-B and
W6-A fixes (`player2.log`) failed W6-C first, and the container and row checks below it did not execute.

## 3. Verification matrix

```text
UNIT_DEBUG=930/930        (64 classes, 0 failures)
UNIT_RELEASE=930/930
DASHBOARD=213/213         (5 suites)
INSTRUMENTED=28/28        (0 failures - the gate W12.1 could not close)
EXAMPLE_ATTEMPT_1=36/36   (2026-09-29-004554: reload after the instrumented wipe)
W6=FAIL                   (w6-dpad-reaches-the-first-card; A and B not reached in that run)
PLAYER=FAIL               (same single check)
FULL=not read before the phase budget ended
EXAMPLE_ATTEMPT_2/3      not run before the phase budget ended
SECURITY_INVARIANT=PASS   (unchanged; the example run asserts the unapproved card plays nothing)
```

The example tier's own 36 checks all passed in the post-instrumented reload run, which also proves the
fixture was restored correctly after the instrumented suite wiped it.

## 4. What is left, exactly

1. **W6-C navigation** (above): walk by the projection's row index with per-press verification. Then
   re-run the player and full tiers, which will also exercise W6-A and W6-B for the first time.
2. **FULL tier and example attempts 2–3** were not completed inside this phase's budget.
3. `D7_PIXEL_LEVEL=UNMEASURED` still stands; the observable-state claim was disproven in W12 and this
   phase did not add pixel infrastructure.

## 5. Commits

* `dfd4ae7` — `test: fix legacy W6 TV harness assumptions, and the instrumented PIN fixture`
  (`tv-app/scripts/tv-e2e.ps1`, `DebugReceiverIntentTest.kt`; **no production source**).

Worktree clean, pushed to `fork`. **No W12 stable tag was created**: a gate is red, and the phase's own
rule is that the tag waits until the matrix is green.
