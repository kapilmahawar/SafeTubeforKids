# Development history

A condensed chronology: the milestones, the stable tags, the defects that mattered, and the guards that
now keep them from coming back. The full reports for most of these phases are kept in [`docs/`](.) as
`W*_VERIFICATION.md`, `W*_IMPLEMENTATION_REPORT.md` and similar; this file is the index that makes sense
of them.

## Stable tags

| Tag | Date | Commit | Meaning |
|---|---|---|---|
| `W8-STABLE-2026-09-27` | 2026-09-27 | — | Dashboard era |
| `W9-STABLE-2026-09-27` | 2026-09-27 | — | YAML catalog import/export |
| `W10-STABLE-2026-09-28` | 2026-09-28 | — | Parent access: PIN, Recovery Code, sessions |
| `W10.1-STABLE-2026-09-28` | 2026-09-28 | — | Parent-access follow-up |
| `W12-STABLE-2026-09-29` | 2026-09-29 | — | Catalog correctness |
| **`W13-STABLE-2026-10-01`** | 2026-10-01 | **`54095dc`** | The hardening checkpoint. Work after this is unreleased. |

## The chronology

**W7–W8 — the parent dashboard.** A browser interface served by the TV: add content, organise it,
preview before saving. W8 and W8.2 were UX audits and their implementation, which is where the
dashboard's visual language and its mobile-first behaviour were settled.

**W9 — the library as a file.** Export the catalog to YAML and import it back, with a preview and a
refusal path for a file that names a source nobody approved.

**W10 — parent access.** The Parent PIN, its salted slow-hash storage, rate-limited attempts, dashboard
sessions, the Recovery Code, and the last-resort reset on the TV behind a typed confirmation. This is
also where the current TV screenshots come from.

**W11 — the first honest library walk.** A fixture library, a device tier that loaded it, and the
discovery that several earlier checks had been passing for the wrong reasons.

**W12 — correctness.** Catalog ordering, hidden items, Continue Watching semantics, and the removal of a
D-pad handler that had been doing the work the UI should have done. W12.1–W12.3 record a device run that
failed for harness reasons before it failed for product reasons, and the harness fixes that followed.

**W13.1–W13.2 — the transport row.** The player's five transport controls could not reliably be reached
with the remote. Two attempts were made, measured and reverted, and the issue was recorded as deferred
(`W13_2_DEFERRED.md`, still in the repository).

The cause, found later by measuring inside the app rather than by reading dumps, was a composition
lifetime rather than a focus graph: **the overlay hides itself four seconds after the last input while
playing, and the transport row is composed only while the controls are visible — so focusing a control
removed that control from the composition and focus fell back to the full-screen surface.** That is
exactly what "Left and Right leave the row" looked like. The fix stands the auto-hide down while a
transport control holds focus, links the five controls to each other with explicit neighbours so the
geometric search can never hand the row to the surface, consumes OK so one press does one thing, and drops
the one-shot entry request so a stale flag cannot pull focus back later. The lesson is recorded in the
code: a focus problem is not always a geometric problem; check what is being composed, and when.

**W13.3–W13.6 — the player's controls.** A focus ring that was invisible on Play/Pause, seek persistence
(a seek that was never stored, and a save that could overwrite a newer one), caption selection pinning the
first text track instead of the chosen one, and the quality chip reporting the previous item's picture.
Each was fixed with the test that would have caught it, and the device `PLAYER` tier grew to 49 checks.

**W13.8 — instrumentation against a real device is destructive.** Running the connected-test task
uninstalled the app on the family television, taking the parent PIN, the Recovery Code and the library
with it — with the credential tests skipped and nothing failing. The task installs its APKs with
`uninstall_after_test = true`, so the uninstall is the task's own behaviour, not a test's. The credential
intents were moved behind an explicit opt-in, and a device-free check now asserts that separation.

**W13.9–W13.10 — recovery, and a guard that fails closed.** The family installation was restored by hand
through the normal setup flow. The Gradle build now refuses `connected*`, `device*` and `uninstall*` tasks
unless the run opts in, refuses before it does any device work, and names no device. The refusal was later
observed at runtime on an attached device, with the instrumented app's install timestamp and process id
unchanged.

**W13.11–W13.12 — the dashboard's first click.** Adding a source reported a failure on the first click and
worked on the second. The cause was a contract mismatch: the server answered `201 Created`, and every
dashboard call site accepted only `200` and `409` — so the first click failed on a source the TV had just
stored, and the retry succeeded only because it answered `409`. Fixed at one tested decision point, with
the add flow gated so a second click cannot become a second write. The same phase removed an arbitrary cap
of 20 sources: a source is one small row, resolution is sequential, and the per-source video limit is where
that cost actually is.

Deploying that fix then exposed a defect in the fix itself: the page loaded a new JavaScript module that
the server's hand-written asset list did not include, so the live dashboard asked for a file it answered
`404` for. Found by fetching the running dashboard rather than by trusting the tests, fixed, and now
guarded by a device-free check that compares what the page loads against what the server serves.

**W14 — the player capability audit.** A read-only audit of what the player actually does: transport,
seek (10 seconds, accelerating when held), speed, quality, audio, captions, screen fit, resume, the
overlay, and the single authorization gate. Its conclusion was that nothing was missing or broken, and
that the gaps were in verification rather than features.

**W14.1 — retry, and a real authorization defect.** Adding the missing coverage found one: `start()`
authorizes before it resolves anything, but `retry()` called `prepare()` directly, which does not. A parent
withdrawing a source while the child is on the error screen left a Retry that would resolve that video's
stream and hand it to the player. `retry()` now asks authorization again. The phase also added coverage for
the audio and speed menus, both ends of the seek range, the key map, and a source-order guard for the
three-way Left/Right contract.

**W14.2 — a safe path to the player.** Because the only way the established harness reaches the player is
one this project will not point at a family television, a separate read-only script now does it by
walking the library with remote keys. It reached the real player on the Mi Box from the UI alone, with all
five transport controls present and focus on the player surface, and left the TV on its launcher.

## Defects worth remembering

| Defect | Cause | Guard that now exists |
|---|---|---|
| Transport row unreachable with the remote | The overlay's auto-hide un-composed the focused control | Auto-hide stands down while a control holds focus; explicit neighbour links; `PlayerKeysTest` and the key-contract source check |
| Retry could play a withdrawn video | `retry()` skipped the authorization check | `retry()` re-authorizes; a deterministic JVM test withdraws a source and requires that nothing resolves |
| First source add reported a failure | Server answered `201`, dashboard accepted only `200`/`409` | The decision lives in one tested module; call sites use it; a guard forbids the old pattern |
| A new dashboard asset returned 404 | The server's asset list is hand-written and was not updated | A device-free check compares the page's assets against the served list, both directions |
| Arbitrary 20-source cap | A limit nobody could justify | Removed; the per-source video limit remains where the cost is |
| Connected instrumentation erased the family TV | The Gradle task uninstalls what it installs | The build refuses device tasks unless a run opts in; device verification has a read-only path |
| Invisible focus on Play/Pause | The ring was drawn in the same colour as the disc | Contrast-based ring, measured, with a unit test on the numbers |
| Quality chip showed the previous item's picture | Rendered size leaked between items | Cleared on prepare; covered by tests |

## Where the historical reports live

Everything above condenses what is recorded in more detail in this directory: the `W*_VERIFICATION.md`
files are the phase records with their device evidence, `SECURITY_MODEL.md` covers the security posture,
`W13_2_DEFERRED.md` preserves the original investigation of the transport row (including the two reverted
attempts and why reading `uiautomator` output misled it), and `W13_8_HARDENING.md` and
`W13_9_RECOVERY_AND_TEST_SAFETY.md` record the destructive-task incident and the guard that followed.

These are kept as engineering evidence rather than as current instructions. Where a historical document
and the code disagree, the code is right, and the document should be corrected rather than quietly
believed.
