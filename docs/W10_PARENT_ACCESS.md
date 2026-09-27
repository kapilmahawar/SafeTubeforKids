# Parent access: the Parent PIN, the Recovery Code, and first-run setup (W10)

SafeTube's parent credential used to be a six-digit number invented when the app started, printed on
the television and encoded in the pairing QR code. It changed on every launch, so it was really a
one-session pairing code: a parent who wrote it down found it useless the next day, and the credential
lived in a scannable image and on a screen anyone in the room could read.

W10 replaces that with one **Parent PIN** the parent chooses, stored as a verifier rather than as a
number, and a **Recovery Code** they write down once. Everything stays on the device: no account, no
email, no cloud, no new backend.

---

## 1. First run

A TV with no Parent PIN opens on setup, and only ever opens on it once:

```text
Welcome to SafeTube
   ↓
Connect your phone          (QR code: the dashboard address, and nothing else)
   ↓
Create Parent PIN           (six digits, twice, on the TV's own keypad)
   ↓
Recovery Code               (shown once; [ I've Saved It ])
   ↓
SafeTube is ready           → the child's library
```

"Does a Parent PIN exist?" is the single persistent fact that decides this, read once when the
navigation host is composed. An installation that predates W10 has a library and approved sources but
no persistent credential, so it gets the one-time setup and **keeps everything else** — the catalog,
the sources, the watch history and the settings are not touched by creating a credential
(`ParentAccessSecurityTest.anExistingInstallationKeepsEverythingAndGetsOneTimeSetup`).

Setup happens on the TV, not on the phone, and for one reason: the Recovery Code is displayed the
moment it is created, and the television is the screen in the house that everyone can see. There is no
way to create a Parent PIN from the dashboard.

## 2. The Parent PIN

* Six digits, chosen by the parent. Digits only, because it has to be typeable on a remote.
* Verified against a **PBKDF2 verifier**: a per-credential random 16-byte salt, 120,000 iterations, and
  the digest recorded alongside it (`PBKDF2WithHmacSHA256` where the device has it, `PBKDF2WithHmacSHA1`
  on API 24/25, which is why the algorithm travels inside the verifier).
* **Never stored, never readable, never shown.** There is no `getCurrentPin()`, no API that returns it,
  and no debug intent that reports it — the old `DEBUG_GET_PIN` now answers only *whether* a credential
  exists.
* Persisted in `parentapproved_parent_access` (SharedPreferences), so it survives process death, app
  restart and reboot. It is not in the catalog database, because a credential is not catalog data.
* Five wrong answers lock sign-in out for five minutes, then ten, then twenty, doubling to a ceiling,
  with the counters persisted so a reboot does not clear them. The PIN and the Recovery Code have
  separate counters, so fumbling one does not spend the other's attempts.

**Honest about its strength:** six digits is a million candidates, so an attacker holding the
preferences file can always enumerate them. 120,000 rounds is what makes that expensive rather than
instant, the rate limiter is what makes it impractical through the dashboard, and the Recovery Code is
the credential that actually carries strength.

## 3. The Recovery Code

```text
SafeTube Recovery Code

8K4P-7M2Q-91TX

Write this down and keep it safe.
This code can be used to reset your Parent PIN if you forget it.
It is shown once. SafeTube cannot show it again.
```

* Generated on the TV with `SecureRandom`, twelve characters from a 32-symbol alphabet (every digit,
  and only the capital letters that cannot be mistaken for one): **exactly 60 bits**.
* Stored as a PBKDF2 verifier, like the PIN. The plaintext exists only in memory, only until the
  parent acknowledges it, and never on disk.
* Dashes, spaces and lower case are forgiven when it is typed back — the same normalisation runs where
  it is created and where it is verified.
* Shown in exactly two places: first-run setup, and the TV's **Settings → Parent access → Recovery
  Code** screen (which asks for the current Parent PIN before issuing a new one). The dashboard can
  also rotate it, from a signed-in session, and shows the new code once.
* Rotating invalidates the previous code immediately. Using it to recover a PIN rotates it as part of
  the same operation, so a code is never a permanent second password.

## 4. Signing in, and changing the PIN

The dashboard asks for the Parent PIN. A correct PIN is exchanged for the **same session token the
dashboard has always used** — the PIN is sent once and is never a bearer token, never a URL parameter
and never stored. A TV with no Parent PIN says so rather than presenting a form that cannot work.

**Settings → Parent access** offers three things:

| Operation | What it does |
|---|---|
| Change PIN | Asks for the current PIN, the new one and its confirmation. Every session issued under the old credential is invalidated, including the one making the change, so the parent signs in again. The library, the approved sources and the Recovery Code are untouched. |
| Generate new Recovery Code | A new code, shown once; the previous one stops working immediately. |
| Sign out all sessions | Invalidates every session, this browser included. |

## 5. Forgot the PIN

```text
Forgot PIN?
   ↓
Enter the Recovery Code generated on your TV   → [ Verify ]
   ↓  (verified first, so a parent never types a new secret into a form that will reject them)
Create your new Parent PIN  (six digits, twice) → [ Reset PIN ]
   ↓
The new Recovery Code, shown once
```

On success: the PIN is replaced, **every** dashboard session is invalidated (including the TV's own
catalog-sync session, which re-issues itself — see `ServiceLocator`), the used Recovery Code is
invalidated and a new one is issued. The catalog and the approved sources are not touched.

## 6. Forgotten both: the destructive reset

The only place this exists is the TV: **Settings → Reset SafeTube** (and, on a TV with no credential,
the setup path). There is deliberately **no HTTP route that can wipe a TV**, because an unauthenticated
request must not be able to erase a child's library
(`SafeTubeResetTest.noUnauthenticatedRouteCanTriggerTheWipe`).

It asks for the phrase to be typed:

```text
I UNDERSTAND THIS ERASES EVERYTHING
```

Exact match required; case and surrounding space are forgiven, a prefix is not, and the words are
printed immediately above the field. Then a **separate** second question:

```text
This cannot be undone.
Erase everything and reset SafeTube?
[ Cancel ]  [ ERASE & RESET ]
```

Only the second confirmation performs the wipe. Afterwards the app returns to first-run onboarding with
the whole back stack gone.

### What the reset wipes, exactly

| SafeTube-owned | Where |
|---|---|
| Parent PIN and Recovery Code | `parentapproved_parent_access` |
| Every dashboard session | `parentapproved_sessions` |
| Both attempt counters | `parentapproved_pin_lockout` |
| The curated catalog | `files/catalog.json`, `catalog.version`, any `.tmp` |
| The TV's mirror of it | `catalog_nodes`, `catalog_metadata` |
| Approved sources and cached videos | `channels`, `videos` |
| Watch history and resume positions | `play_events`, `playback_positions` |
| Time limits, bedtime and bonus minutes | `time_limit_config` |
| Kiosk configuration and app whitelist | `kiosk_config`, `app_whitelist` |
| Remote access configuration, including the TV secret | `parentapproved_relay` |
| The debug catalog-sync switch | `parentapproved_catalog_debug` |
| The crash log | `files/crash_log.txt` |

### What it does not wipe

The Android system and its settings; other applications and their data; anything in shared or external
storage; SafeTube's own APK. Kiosk *device-owner* state belongs to Android, not to this app: the reset
asks the kiosk manager to stop and releases lock-task mode, which is the most an app may do.

Note what is **not** in the "kept" list: the approved sources are erased. That is the point of a
destructive reset — a reset that kept the library but dropped the credential would be a way to read a
child's library without the PIN. What the reset must never do is leave the TV able to play something it
could not play before, and after a wipe even a previously approved video plays nothing
(`ParentAccessSecurityTest`).

## 7. Why a credential cannot become permission

Parent access answers *"is this the parent?"*. Playback authorization answers *"may this video play?"*,
from two tables (`videos`, `channels`) and nothing else. They do not share a code path, and
`ParentAccessSecurityTest` proves it by doing every parent-access operation there is — creating a PIN,
signing in, verifying and using a Recovery Code, changing the PIN, rotating the code, wiping the
device — and checking that the playback answer has not moved.

* A Parent PIN, a Recovery Code, a session or a pairing token **cannot** approve a YouTube source,
  bypass a source restriction, or make an unapproved video playable.
* The QR code contains only the dashboard address. There is no pairing token, because one is not
  needed: the parent signs in with what they know.
* Adding a catalog entry for an unapproved video still plays nothing (W9's invariant, unchanged).

## 9. What the real device found

W10 was verified on a Mi Box 4 (Android 12), and the device found four things that no unit test could
have. All four were fixed, and all four are worth recording, because each one was invisible from the
JVM suite and each would have reached a family:

| Found on the device | What it looked like | Fix |
|---|---|---|
| **An ANR during setup** | Creating the Parent PIN blocked the TV's input for eleven seconds (`Input dispatching timed out ... Waited 5006ms for MotionEvent`, 102% CPU) and Android force-finished the activity, so setup crashed and the parent was dropped back to the launcher. Cause: PBKDF2 on the UI thread, at 120,000 iterations | The derivation runs off the main dispatcher (`Dispatchers.Default`), and the iteration count was **measured** on this hardware and lowered to 30,000 (~1s per derivation instead of ~5.5s) |
| **The confirmation keypad was off-screen** | The Create PIN step stacked two keypads, which is taller than a 1080p television: the confirmation's buttons sat below the visible area with no way to scroll to them, so setup could not be completed at all | The two keypads sit side by side |
| **The keypad moved while typing** | `●` and `○` are different sizes in this font, and the screen centres its content - so the first digit typed shifted the whole keypad up by 41px, under the parent's finger | Every position is a fixed-size box |
| **The reset screen could not be confirmed** | With the phrase field focused, the television's keyboard owns the remote: taps on Continue and Cancel were swallowed, directional keys moved the keyboard's selection, and Back closed the keyboard *and* popped the screen, taking the typed phrase with it. On the one screen that erases everything, a parent could type the phrase and then have no way to press the button | The field hands the remote over on Down/Enter (closing the keyboard and focusing Continue), the keyboard's Done key does the same, and Back closes the keyboard without leaving the screen while it is up |

The verification itself is two runs: the W10 harness drives the whole flow (setup, QR, sign-in,
restart, change PIN, recovery, rotation, and the credential-versus-playback invariant) in a real
browser against the real TV, and a focused script drives the destructive reset through both gates on
the TV's own UI, checking after each gate that nothing has been erased yet.

```text
device: MIBOX4 (Android 12, 1920x1080 @ 320dpi)
setup:  Welcome -> QR -> Create PIN -> Recovery Code (TCNJ-EERF-0GR0) -> Ready -> child library
sign-in: PIN accepted, wrong PIN refused ("That PIN was not right")
restart: pinConfigured=true, no onboarding, dashboard reachable again
change PIN: page signed out, old PIN refused, new PIN works
recovery: TV code accepted, new PIN works, used code refuses (401), new code works (200)
reset:  phrase printed -> "yes please erase it" refused, nothing erased
        -> "I UNDERSTAND THIS ERASES" refused, nothing erased
        -> exact phrase -> "Erase everything and reset SafeTube? [Cancel] [ERASE & RESET]"
        -> Cancel: nothing erased
        -> ERASE & RESET: "Welcome to SafeTube", sources 0, videos 0, events 0, sessions 0,
           pinConfigured false, catalog empty, GET /auth/state {configured:false}
```

## 10. Where it lives

| Concern | File |
|---|---|
| The credential: setup, verify, change, recover, rotate | `auth/PinManager.kt` |
| Where it is stored | `auth/ParentCredentialStore.kt`, `auth/SharedPrefsParentCredentialStore.kt` |
| Hashing | `auth/SecretHasher.kt` |
| The code format and its entropy | `auth/RecoveryCode.kt` |
| The lockout, unchanged from the ephemeral model | `auth/AttemptLimiter.kt` |
| HTTP surface | `server/AuthRoutes.kt` (`GET /auth/state`, `POST /auth`, `/auth/pin`, `/auth/recovery`, `/auth/recovery/verify`, `/auth/recovery/rotate`, `/auth/sessions/revoke`) |
| First-run flow | `ui/screens/OnboardingScreen.kt`, `ui/components/PinKeypad.kt` |
| Recovery code on the TV | `ui/screens/RecoveryCodeScreen.kt` |
| The destructive reset | `ui/screens/ResetSafeTubeScreen.kt`, `reset/SafeTubeReset.kt` |
| The dashboard's side | `assets/parent-access.js`, `assets/app.js` |

## 11. Testing it

```bash
# JVM: the credential, the routes, the reset scope, and the separation from playback authorization
cd tv-app && ./gradlew testDebugUnitTest --tests '*ParentAccess*' --tests '*PinManagerTest*' \
  --tests '*RecoveryCodeTest*' --tests '*SafeTubeResetTest*'

# The dashboard's parent-access model and guards
cd tv-app && node --test scripts/dashboard-parent-access.test.js

# On the device, the harness installs a PIN it knows through the debug-only instrument. It is sent to
# the receiver by name: a package-scoped implicit broadcast was not delivered after a destructive
# reset, which made a regression run look like a broken sign-in.
#   am broadcast -a tv.safetubeforkids.app.DEBUG_SET_PIN \
#     -n tv.safetubeforkids.app/.debug.DebugReceiver --es pin 482913
cd tv-app && ./gradlew assembleDebug
cd tv-app/scripts && powershell -File tv-e2e.ps1 -Tier player -SkipBuild
```

`DEBUG_GET_PIN` no longer returns a PIN — there is none to return. `DEBUG_SET_PIN` installs one for
automated runs, `DEBUG_RESET_PIN` clears the credential, and `DEBUG_FULL_RESET` performs the same wipe
the TV's reset screen does. All three are debug-build only.
