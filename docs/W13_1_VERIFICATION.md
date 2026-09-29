# W13.1 — Parent-gating the destructive TV Settings actions

* Baseline: `5e6a22ef4b66faab33e1d2916de90a4aa1832843` (one documentation commit past the W12 stable tag,
  which peels to `2acf8276119ad8b600b5e49c61469fac05ec0e5e`). Worktree clean at the start.
* Device: Mi Box 4 (MIBOX4, Android 12).
* Production files changed: **4** (3 modified, 1 added) plus 2 UI/test additions. No schema change, no
  migration, no change to playback authorization, the queue, the resolver, resume, Continue Watching or
  the player.

## 1. The defect

`SettingsScreen` performed its own destructive work with a plain `onClick`, on a screen a child reaches
from the home screen's top bar without any credential:

```kotlin
SettingsBtn("Sign Out All Sessions") { ServiceLocator.sessionManager.invalidateAll() }
SettingsBtn("Clear Events") { PlayEventRecorder.clearAll() }
```

`clearAll()` deletes every row of `play_events`, and that table is exactly what the daily limit and
bedtime are computed from - `RoomWatchTimeProvider.getTodayWatchSeconds()` →
`PlayEventDao.sumDurationToday(dayStartMillis)` → `TimeLimitManager.canPlay()`. Two presses on the
remote therefore reset the day's screen time and let the child keep watching. That is a hole in a
parent-configured safety control, which is why this phase went first. `Sign Out All Sessions`
invalidated every parent session the same way.

A second, quieter problem was found while looking: the three destructive buttons sat under the
**`Debug`** heading (the `Parents` heading above them was empty), which is how they came to read as
developer leftovers.

## 2. The classification of every Settings action

Required by the phase, and now written into the source (`auth/ParentGate.kt`) so it cannot drift:

| Action | Class | Evidence |
| --- | --- | --- |
| version, `Parent PIN: set / not set up yet`, `Active sessions: N`, recovery-code state | READ_ONLY | `SettingsScreen.kt` renders state only |
| `Refresh Videos` | CHILD_SAFE_MUTATION | `HomeViewModel.refresh()` → catalog sync; deletes nothing, approves nothing, cannot change what may play |
| `Recovery Code` | PARENT_ONLY_MUTATION, **already gated** | `RecoveryCodeScreen.kt:139` checks the current Parent PIN before rotating the code |
| `Sign Out All Sessions` | PARENT_ONLY_MUTATION | **gated in this phase** |
| `Clear Watch History` (was `Clear Events`) | PARENT_ONLY_MUTATION | **gated in this phase** - the defect above |
| `Reset SafeTube` | PARENT_ONLY_MUTATION, **deliberately left as designed** | see §5 |
| `Debug` row (offline sim, log panel) | debug builds only | `BuildConfig.IS_DEBUG`; absent from a family build |

## 3. The implementation

**`auth/ParentGate.kt` (new).** One boundary that verifies *and* performs, so no caller can do either
half on its own:

* `ParentOnlyAction` - the two gated actions, each carrying the label the button shows and the sentence
  the prompt explains it with.
* `ParentGateResult` - `Authorized` or `Refused(message)`; there is no third outcome and nothing is
  performed on `Refused`.
* `ParentGate.run(action, pin)` - the PIN is checked with `PinManager.validate`, which is the call the
  parent dashboard signs in with, and the mutation is performed in the same function body. The class's
  only public method is `run`, which a unit test asserts by reflection.

**What it reuses, exactly.** `PinManager.validate` → `checkPin`, so a wrong PIN spends the *existing*
persisted attempt and the fifth starts the *existing* escalating lockout (`AttemptLimiter`: five tries,
5 minutes, doubling to a 60-minute ceiling, persisted in SharedPreferences); a right PIN issues an
*existing* dashboard session through `PinManager`'s own `onPinValidated` hook. There is no second PIN,
no second limiter, no new session lifetime, no persisted "authorized" flag and no `isParent` state. A
short PIN is refused as a typo before the limiter is touched, which is the same distinction
`verifyRecoveryCode` makes for a malformed Recovery Code. A TV with no PIN configured is **refused**
(fail closed), which costs nothing because the navigation host sends an unconfigured TV to onboarding
to choose one.

**`ui/components/ParentPinPrompt.kt` (new).** The question, over the Settings screen: the action's name,
what it will do, the existing `PinKeypad`, the error line, Confirm and Cancel, and a BackHandler so the
remote's Back leaves without changing anything. It performs nothing itself - it hands the PIN to
`ServiceLocator.parentGate` and reports what came back.

**`ui/screens/SettingsScreen.kt` (changed).** The two buttons open the prompt instead of mutating;
`Parents` now holds the parent actions and `Debug` holds only the debug tools; the two labels come from
`ParentOnlyAction` so the button and its prompt cannot drift. `PinKeypad` gained an optional
`initialFocus` (a prompt over another screen has to say where the remote is, or the first press goes to
the button underneath); the parameter is optional, so onboarding and the recovery screen are unchanged.

## 4. Why the mutation is really behind the gate

Hiding a button is not a gate, so the mutation itself moved. `PlayEventRecorder.clearAll()` and
`SessionManager.invalidateAll()` are now called from exactly one place - `ServiceLocator.parentGate`'s
wiring - and a source-scanning test (`SettingsParentGateTest`) asserts both the call-site list and that
the Settings screen contains neither call, with comments stripped so documentation cannot fake a call
site. The wipe path is unchanged and unreachable without its typed phrase (W12's
`SafeTubeResetTest.noUnauthenticatedRouteCanTriggerTheWipe` still holds).

## 5. The one deliberate exception: `Reset SafeTube`

It is destructive and it is classified PARENT_ONLY_MUTATION, but it was **not** changed, for a reason
that is in the product's own words: `ResetSafeTubeScreen` and `SafeTubeReset` document it as the way
back in for a parent who has lost **both** the Parent PIN and the Recovery Code ("there is no third
secret… the only honest way back in is to put the device back to new"), and there is deliberately no
HTTP route that can wipe anything. Its existing barrier is a typed five-word phrase plus a second,
separate question. Putting a credential in front of it would delete the only way back in for the person
that screen exists for. **This needs an owner decision**, and the candidate design is "the Parent PIN
**or** the Recovery Code" - both existing credentials, both verified by `PinManager` - which would close
the child path while leaving a way in for a parent who still has their paper code.

## 6. Evidence

### Unit (`./gradlew --offline testDebugUnitTest testReleaseUnitTest`)

```text
UNIT_DEBUG   946/946, 0 failures   (930 before + 16 new)
UNIT_RELEASE 946/946, 0 failures
```

`ParentGateTest` (11 tests) covers: no authorization ⇒ nothing performed; a TV with no PIN ⇒ refused
and nothing performed; the right PIN ⇒ performed once, leaving the *existing* session behind; each
action running only its own mutation; signing out ending with no session left; the wrong PIN ⇒ refused,
nothing performed, "4 tries left" then "3"; a short PIN ⇒ refused as a typo without spending an
attempt; the existing lockout ⇒ the right PIN refused while it stands, and the manager is locked out
too; a second gate over the same credentials asking again, with nothing written to the credential store;
`run` being the only public method; and the action list being exactly the two gated ones.

`SettingsParentGateTest` (5 tests) guards the sources: the watch-history reset has exactly one call site
and it is the gate's wiring; `invalidateAll` has exactly five, each authenticated or deliberate; the
Settings screen offers the actions only through the prompt; the prompt asks the gate.

### Device (Mi Box 4, over ADB, script and dumps in `%TEMP%\w131\`)

```text
baseline            today=312s eventsToday=29 allTime=29, confirmed stable (nothing playing)
child attempt       Settings -> Clear Watch History -> "Parent PIN needed" prompt appears
cancel (BACK)       prompt dismissed, still on Settings, watch time still 312s/29   => nothing performed
wrong PIN (111111)  "That is not the Parent PIN. 4 tries left before a wait."        => nothing performed
                    watch time still 312s/29
correct PIN         "Watch history cleared. Today's watch time counts from zero again."
                    now today=0s eventsToday=0 allTime=0                           => the reset really ran
afterwards          foreground still the app, process alive, no crash
```

Every key press was a D-pad press; focus was found by walking the real button positions (my first two
attempts guessed the keypad geometry and were wrong - recorded here so nobody repeats it). The unit
suite covers the lockout itself; the device run deliberately did **not** burn the parent's real five
attempts, and the "4 tries left" message is the evidence that the device path uses the real limiter.

### Harness (the W12 regression foundation)

```text
EXAMPLE (built and installed, then run)   36/36 PASS, 0 failed
INSTRUMENTED                              28/28, 0 skipped, 0 failed
PLAYER                                    41/41 PASS, 0 failed (includes W6 10/10 and both seek checks)
FULL, run 1                               53/54 - FAIL: seek-backward position 38s -> 32s
FULL, run 2                               53/54 - FAIL: end-of-video-handling (queue-finished oracle)
FULL, run 3                               53/54 - FAIL: end-of-video-handling ("was GsrCSM_agk0 at 0s of 186s")
W6 (inside every run above)               10/10 PASS, every run
security (inside every run above)         app-foreground-at-security, unapproved-video-blocked,
                                          unapproved-video-not-playing, api-refuses-unauth-read,
                                          api-refuses-unauth-write - all PASS, every run
```

**The full-tier failures are not caused by this change.** They are in code paths this phase does not
touch (the diff is `ServiceLocator`, `PinKeypad`, `SettingsScreen`, `ParentGate`, `ParentPinPrompt` -
no player, key-handling, queue or recorder code), they are not even the same failure twice, and both are
checks the repository already documents as fragile:

* `seek-backward` presses two seek-back keys 700 ms apart and requires the playhead to fall by 8 s
  while playback keeps running; one coalesced press leaves about -6 s, so the check passes or fails on
  a second or two of drift. It passed in run 2 (`26s -> 10s`), in run 3 (`26s -> 9s`) and in both W12.3
  runs (`25s -> 10s`, `25s -> 9s`); it failed once, in run 1. Classified
  **HARNESS_DEFECT (marginal timing), pre-existing**.
* `end-of-video-handling` failed in runs 2 and 3 with the app's own log as the oracle. Run 3's own
  diagnostic says why: `was GsrCSM_agk0 at 0s of 186s` - the seventeen seek presses did not land at all,
  which the check's source comments already record as an observed case ("observed as a baseline of
  '0s of 165s'"), so the tier then waited out a 186-second video and read a transient empty
  `currentlyPlaying` during the queue hand-over as "playback stopped". W12.1 §10 had already recorded
  this check as **not measured** against the example library (its videos are too long to play out
  inside a tier), and its comments list four earlier false results of the same shape. It passed in run 1
  (via the branch where the seeks overshoot and the queue advances) and in W12.3. Classified
  **HARNESS_DEFECT (unstable baseline + log oracle read up to three minutes later), pre-existing**.

Neither is a reason to weaken an assertion, and neither was touched by this phase. They are recorded
because the full tier is the regression baseline: its two unstable checks should be fixed as their own
piece of work - assert on the app's own queue state instead of a log line that may have been evicted,
and treat a transient empty status as "unknown, retry" rather than "playback stopped".

## 7. Commits

```text
ffc6063  fix(settings): put the TV's destructive actions behind the Parent PIN   (production)
2b0d0b0  test: guard the Parent PIN gate and the mutation call sites it owns     (tests)
<docs>   docs: record W13.1 - the parent gate, its evidence, and two unstable baseline checks
```

Pushed to `fork` only. `origin` untouched, no force-push, and `W10-STABLE-2026-09-28`,
`W10.1-STABLE-2026-09-28` and `W12-STABLE-2026-09-29` are unchanged. **No W13 tag was created** - that
decision follows the report, and the tag rule for W13 required W6, PLAYER, FULL and EXAMPLE_STABILITY to
be green, which FULL currently is not for the two pre-existing harness reasons above.
