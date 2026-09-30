# W13.1b — Securing the destructive `Reset SafeTube`

* Baseline: `bb1d9b7f58dfb7a9c5e98d89f36f0afd5e442d51` (W13.1a).
* Device: Mi Box 4. Television clock corrected during the phase (see §6); no other environment change.
* Scope: the reset flow, its authorization boundary, its tests and its documentation. No player, catalog,
  resolver, authorization, resume or schema change.

## 1. What was wrong

The W13.1b audit established that `SafeTubeReset.wipe(context)` took no credential at all: it was a
public `suspend fun` that deleted the catalog document, the TV's catalog mirror, the approved sources,
watch history, resume positions, time limits, kiosk configuration, the whitelist, the Parent PIN, the
Recovery Code, every session and both attempt counters - and the only thing in front of it was a phrase
**printed on the screen it was typed into**. The phrase proves intent (it makes a person read what the
button does) and it proves nothing else: anyone who can read can copy it. A child who could do that
erased the family's configuration *and* took ownership of the TV, because the app then returns to
first-run setup where the next person chooses the Parent PIN - after which they can approve content the
real parent never approved.

## 2. The policy now

```text
Settings → Reset SafeTube
   → Parent PIN  OR  Recovery Code      (ResetGate; verified, limiter and lockout as everywhere else)
   → the existing phrase, typed          (confirmation of intent, unchanged)
   → "Erase everything and reset SafeTube?"  → [ ERASE & RESET ]
   → SafeTubeReset.wipe(context, authorization)   (the proof is a parameter)
   → first-run setup
```

**The authorization boundary is a type.** `SafeTubeReset.wipe` now requires a `ResetAuthorization`,
which can only be produced by `ResetGate` after `PinManager.validate` (PIN) or
`PinManager.verifyRecoveryCode` (Recovery Code) has succeeded. A caller that wants to wipe a television
must obtain a verified parent credential, or the code does not compile; `ResetMutationBoundaryTest`
reads the sources and fails if a second construction site or a second caller ever appears.

**The two credentials are the ones the product already trusts.** No third secret, no second limiter, no
second session type, no new persisted state, no database change. The PIN path spends the existing
persisted attempt and starts the existing escalating lockout (5 tries, 5 minutes doubling to 60), and
issues the existing dashboard session; the Recovery Code path uses its own separate counter and lockout,
treats a mis-typed code as a typo rather than a guess (`NotWellFormed`), and - deliberately - does not
rotate the code: verification is not use, and the wipe destroys the credential store anyway.

**The phrase and the second question stayed.** A credential does not make anyone less likely to press
through a dialog, and the phrase is still the only part of the flow that cannot be completed by pressing
a button twice.

**Both credentials lost.** This screen is no longer their way back in, and the documentation now says so
plainly: Android's own *Settings → Apps → SafeTube for Kids → Clear storage* (or uninstall and reinstall)
is the path, with the same consequence as the reset. Nothing on the television can distinguish that
person from anybody else holding the remote, so a password-free button was the wrong answer.

## 3. Files

| File | Change |
| --- | --- |
| `auth/ResetGate.kt` (new) | `ResetAuthorization` (opaque proof, `Method` for the audit line), `ResetAuthorizationResult`, and the two verification paths |
| `reset/SafeTubeReset.kt` | `wipe(context, authorization)`; the log line now records which credential authorized it |
| `ServiceLocator.kt` | `resetGate`, separate from `parentGate` because this one accepts two credential kinds and authorizes an irreversible wipe |
| `ui/screens/ResetSafeTubeScreen.kt` | three stages: credential → phrase (unchanged) → second question (unchanged) |
| `debug/DebugReceiver.kt` | `DEBUG_FULL_RESET` now takes a `pin` extra and authorizes through the same gate; no bypass kept for a test instrument |
| `docs/TESTING.md`, `docs/W10_PARENT_ACCESS.md` | the reset's coverage corrected (no script exists), the new flow, the phrase's role, the lost-both path, the debug instrument's new contract |

## 4. Tests

```text
ResetGateTest                 12/12   no credential authorizes nothing; the right PIN and the right code
                                     authorize and say which one did; a wrong one is refused with the
                                     limiter's own message; a malformed one is a typo and spends nothing;
                                     both lockouts apply and are separate; the code is not rotated
ResetMutationBoundaryTest      5/5    the wipe's signature requires a proof; only the reset screen and the
                                     debug instrument call it; only the gate mints one; the instrument
                                     presents a credential; the credential store has no third clearer
SafeTubeResetTest              8/8    unchanged scope assertions, now through a credential (they no
                                     longer compile without one)
ParentAccessSecurityTest      10/10   a credential is still not permission, reset included
```

## 5. Device evidence (Mi Box 4)

```text
baseline            catalog nodes=46 version=2 sources=5
Settings            the Parents section shows the new copy; "Reset SafeTube" reachable by D-pad
child attempt       pressing it shows "A parent has to approve this. Entering the Parent PIN or the
                    Recovery Code authorizes erasing everything SafeTube stores on this TV."
BACK                leaves the flow; catalog still nodes=46 version=2 sources=5  -> nothing erased
wrong PIN           111111 -> nothing erased (state identical)
wrong Recovery Code "000000000000" -> "A Recovery Code is 12 characters, in three groups of four."
                    (refused; the field had picked up a stray leading character, so the gate saw a
                    malformed code - the refusal path, with nothing erased)
correct PIN         482913 -> the phrase stage appears ("Type: I UNDERSTAND THIS ERASES EVERYTHING")
wrong phrase        "I UNDERSTAND" -> "That is not the phrase. Nothing was erased." and the catalog is
                    still nodes=46 version=2 sources=5                            -> phrase guard holds
correct phrase      "I UNDERSTAND THIS ERASES EVERYTHING" -> "Erase everything and reset SafeTube?"
                    -> ERASE & RESET -> the app is at "Welcome to SafeTube / Set up SafeTube from your
                    phone and create a Parent PIN." and the credential API no longer authenticates
                    -> the reset ran, and only with a credential
recovery code       rotated on the TV and read back (NCXD-X5BB-NQ0P); the authorization attempt was
                    refused because the on-screen keyboard prefixed the typed code (see §6) - the
                    successful recovery-code path is asserted by ResetGateTest, not on the device
drops               none: every key press was gated on the app being in front
```

## 6. Two things this phase learned the hard way, recorded so nobody repeats them

1. **A throwaway verification script needs the harness's foreground guard.** The first attempts drove
   the owner's Android TV launcher: one BACK too many leaves SafeTube, and every later press went to
   whatever was in front. The script now checks the focused package before each key, relaunches the app
   and drops the key when something else is in front - the same rule the harness got in W12.3.
2. **Navigation has to be measured, not assumed.** On the library screen the top bar's Settings button
   is an icon with a `content-desc` and no text (a lookup that matched only `text` found nothing and the
   walk pressed DOWN onto a video card), and a fresh start of the activity puts focus on the bar's
   leftmost button, two RIGHTs from Settings. `adb shell input text` also prefixed the typed Recovery
   Code with a stray character, which is why that step exercised the malformed-code refusal instead of
   the wrong-code one.

The television's clock was also found at its firmware default (*Oct 11 2023* after a reboot), which made
every HTTPS resolve fail with `Unacceptable certificate: CN=WR2, O=Google Trust Services`; re-arming
automatic time fixed it. That is environment, not code, and it is recorded because it looked exactly
like a resolver failure.

## 7. Regression

```text
UNIT_DEBUG          963/963
UNIT_RELEASE        963/963
INSTRUMENTED        28/28
W6                  10/10 (inside the FULL run)
PLAYER              41/41
FULL                54/54
EXAMPLE             36/36
SECURITY_INVARIANT  PASS (unapproved-video-blocked, unapproved-video-not-playing, api-refuses-unauth-read,
                    api-refuses-unauth-write, app-foreground-at-security - all inside the FULL run)
```

One earlier EXAMPLE run reported 34/36 with two D1 resume-report failures (`A offers s`, `landed at
-1s`), immediately after the television had rebooted and dropped off the network mid-run. It did not
reproduce: the re-run was 36/36 with no change to that code. Classified **ENVIRONMENT_FAILURE**, and
recorded rather than quietly re-run.

## 8. What did not change

`PlaybackAuthorization`, the approved queue, the resolver, the player, D1-D6, Continue Watching, resume,
the catalog and playlist models, the example fixture, the database schema (no migration), and the W6
harness. The reset still deletes exactly the same SafeTube-owned state it deleted before: the scope
tests are unchanged and still pass.
