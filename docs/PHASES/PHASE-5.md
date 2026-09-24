# Phase 5 — Media3 playback modernization (verification-first)

**Status: complete.** Phase 4 is the frozen baseline. Phase 5 modernizes nothing that was already
modern: the audit below established that the player **is already** an AndroidX Media3/ExoPlayer
Android TV player, so Phase 5 is a verification-and-hardening phase rather than a rewrite.

Baseline: `1b35c5c` (Phase 4 resolution commit). This phase adds tests and documentation only —
**no application source file was changed.**

## Planned Scope

The Phase 5 brief asks to "replace/modernize the existing playback implementation with AndroidX
Media3 / ExoPlayer-based TV playback and controls", while preserving every Phase 4 authorization,
catalog, navigation, synchronization, offline and resume guarantee. Explicitly out of scope:
redesigning the catalog UI or home screen, changing card dimensions or ordering, introducing
recommendations or search, altering playback authorization, rewriting catalog synchronization,
fixing unrelated resolver/audio-source problems, and general architecture rewrites.

## The finding that shaped the phase

The brief presumes a pre-Media3 player in need of replacement. There is none. The playback stack at
the baseline was already Media3, and had been since Phase 1:

```text
AppNavigation ──► PlaybackScreen   (routed wrapper: owns PlaybackController, its lifecycle
                        │           and the PlaybackCommandBus; 2.5 KB)
                        ▼
                  TvPlayerScreen    (Media3 PlayerView inside AndroidView with the built-in
                        │           controller disabled, plus a hand-written Compose TV
                        │           control layer; 697 lines)
                        ▼
                  PlaybackController.player : androidx.media3.exoplayer.ExoPlayer
```

Dependencies already pinned in `tv-app/app/build.gradle.kts:124-127`:
`androidx.media3:media3-exoplayer`, `media3-exoplayer-dash` and `media3-ui`, all at `1.11.1`.

Auditing the brief's own P5.1 deliverable list against `TvPlayerScreen.kt`:

| P5.1 requirement | Present | Evidence |
|---|---|---|
| Media3/ExoPlayer playback | yes | `ExoPlayer.Builder` (`PlaybackController.kt:96`), `prepare()` (:410) |
| Media3 surface | yes | `androidx.media3.ui.PlayerView` in `AndroidView` (`TvPlayerScreen.kt:296`) |
| TV-friendly controls | yes | Compose layer above; `setShowBuffering(SHOW_BUFFERING_NEVER)` (:302) |
| play/pause | yes | `onTogglePlayPause` → `PlaybackController.togglePause` |
| seek forward / backward | yes | `onSeekBy` → `PlaybackController.seekBy`, `DpadKeyHandler` |
| progress, duration, position | yes | driven from the Media3 player state |
| buffering / loading state | yes | `playbackState == Player.STATE_BUFFERING` (:125) → `CircularProgressIndicator` (:330) |
| playback completion | yes | `Player.STATE_ENDED` handling in `PlaybackController` |
| error state | yes | `errorMessage` (:92) with a focusable Retry that takes focus on error (:149-150) |
| BACK navigation | yes | `onKeyEvent` (:169) → `DpadKeyHandler` |
| DPAD navigation | yes | `onKeyEvent` + `FocusRequester` (:115-116) |
| focusable TV controls | yes | surface and retry are both focus-managed |

It is also TV-correct in ways worth recording, because a rewrite would have risked them:

- Controls never auto-hide while paused, buffering, in error, or while the time-limit warning shows
  (`TvPlayerScreen.kt:130-133`) — a child cannot be left staring at a paused video with no controls.
- On error the **retry button is given focus automatically** (:149-150), so a remote-only user is
  never stranded on an unfocusable screen.
- The next item is `onNextApproved` — driven by the SafeTube approved queue, never by anything
  Media3 or YouTube might offer.

Replacing this working, device-verified player would have put focus handling, the nine verified
resume sub-cases and 43 full-tier assertions at risk for no functional gain, which the brief itself
forbids (§7 "avoid unnecessary rewrites"; §3A "do not perform a general architecture rewrite";
§4 freezes resume and navigation behaviour). The phase therefore closes the **genuine** gap: the
absence of tests that pin the architectural invariants Media3 must never be allowed to break.

## What Phase 5 implemented

One new test class and documentation. Nothing else.

```text
tv-app/app/src/test/java/tv/safetubeforkids/app/playback/PlaybackAuthorizationTest.kt   (8 tests)
docs/PHASES/PHASE-5.md                                                                  (this file)
AI_CONTEXT.md, docs/TESTING.md                                                          (updated)
```

`PlaybackAuthorization` is the most security-critical class in the application and, before this
phase, it had **no dedicated test class** — it was only exercised indirectly through the home-screen
flow tests. The new tests pin the invariants directly:

| Test | Invariant pinned |
|---|---|
| `anApprovedVideoWhoseSourceIsStillPresentIsAllowed` | `AUTHORIZATION → REQUIRED FOR PLAYBACK` |
| `aVideoThatWasNeverApprovedIsDenied` | `CATALOG ≠ AUTHORIZATION` |
| `aVideoFromASourceTheParentRemovedIsDeniedEvenThoughItIsStillCached` | `PLAYABLE URL ≠ AUTHORIZATION` |
| `aVideoCachedForADifferentSourceIsDeniedForThisOne` | no authorization by association |
| `theApprovedQueueIsExactlyTheCachedVideosInTheParentsOrder` | `PLAYER QUEUE ≠ YOUTUBE` |
| `theApprovedQueueIsEmptyForASourceThatIsNotApproved` | no queue to discover content in |
| `theQueueShrinksWhenTheSourceIsWithdrawn` | no stale queue to advance through |
| `everyQueueEntryIsItselfIndividuallyAuthorized` | queue membership is not a permission |

The third test is the important one for a Media3 migration: the video row deliberately **stays in the
approved cache** after the source is withdrawn, so it remains fully resolvable, and the gate must
still refuse it. That is the executable form of "a playable URL is not proof of authorization".

### Why the tests were not duplicated

The home-screen flow tests already cover the catalog-facing side (`aCatalogEntryWithoutApprovalCannotPlayAndOneWithApprovalCan`,
`aCatalogPlaylistCardStillCannotPlayAnUnapprovedPlaylist`, `thePlaylistStartingPointIsTheFirstApprovedVideoOrNothing`),
and the harness covers the route-level side (`unapproved-video-blocked`, `unapproved-video-not-playing`,
`deeplink-plays-nothing`, `extras-cannot-start-playback`). The new class exercises the gate itself and
deliberately does not restate those. Catalog visibility is already pinned by the nine F6 tests added
in `1b35c5c`, so `CONTINUE WATCHING ⊆ CURRENT VISIBLE CATALOG` needed no new test.

## Security implications

**None.** No application source was modified, so no security behaviour could change. The phase adds
tests that would fail if a future change let Media3, a resolvable URL, or catalog membership become a
substitute for `PlaybackAuthorization`. `PlaybackAuthorization`, `CatalogSyncService`,
`CatalogUiProjection` and `CatalogStore` are byte-identical to the Phase 4 baseline.

## User-visible behaviour

Unchanged, by construction: no source file changed. Catalog ordering, card geometry, Continue
Watching curation, resume prompt/Resume/Start over, captions, player controls, error handling and
offline startup all behave exactly as Phase 4 verified them.

## Tests

| Suite | Phase 4 baseline | Phase 5 |
|---|---|---|
| Unit tests | 556/556 | **564/564** (556 + 8 new), 47 classes, 0 failed/errors/skipped |
| Instrumented | 19/19 | see verification table below |
| Player tier | 32/32 | see verification table below |
| Full tier | 43/43 | see verification table below |

No existing test was modified, weakened, skipped or deleted. Every Phase 4 test still passes
unmodified — which is the point of a verification-first phase.

## Migration

None required, and none performed. No schema change (Room stays at version 7), no wire-contract
change (catalog schema stays at `schemaVersion = 1`), no persisted-state change. Existing resume
positions, catalog state, authorization state and settings are untouched, because no code that reads
or writes them was changed. A Phase 4 installation upgrading to this build needs no migration step.

## Known limitations

1. **The audio checks** (`audio-menu-lists-real-tracks`, `audio-switch-applied`) still fail. This is a
   pre-existing **test-media limitation**, not a player capability gap and not a Phase 5 regression:
   the resolver reports `0 audio` for the videos the fixture can reach, so there are no real alternate
   tracks to select. Media3's track-selection API is present and used; with media that actually
   carries multiple audio tracks the same code path would offer them. Not worked around, and no
   tracks were fabricated.
2. **The emulator cannot run on this host** (`emulator -accel-check` exits 3: no virtualization
   support). The AVD and TV system image exist, so this is environmental. Physical TV is authoritative.
3. **Visual review is not possible** for this agent (no image input; `adb screencap` returns a
   100 %-black frame while a video surface is active). Geometry and pixels can be measured
   programmatically, which is not a visual pass and is not reported as one.

## Regression invariants

`CATALOG ≠ AUTHORIZATION`, `AUTHORIZATION → REQUIRED FOR PLAYBACK`, `MEDIA3 ≠ AUTHORIZATION`,
`PLAYABLE URL ≠ AUTHORIZATION`, `PLAYER QUEUE ≠ SAFE TUBE CATALOG`,
`YOUTUBE RECOMMENDATIONS ≠ SAFE TUBE CONTENT`, `CONTINUE WATCHING ⊆ CURRENT VISIBLE CATALOG`.

Each is now backed by an executable assertion (new tests above, or the Phase 4 tests they inherit),
rather than being an architectural intention that nothing checks.
