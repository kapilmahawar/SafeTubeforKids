# W12 — playback and catalog correctness

* Baseline: `5ac84db29f568e46be9030b8ee6ef04a4fe27681` (W11), worktree clean.
* Date: 2026-09-28. Device: Mi Box 4 (`MIBOX4`, Android 12), ADB `172.16.1.2:5555`.
* This phase fixed defects and verification gaps. It did not add features, and it changed no
  architecture: Media3, Room, the catalog model and `PlaybackAuthorization` are as they were.

```
SAFE_TUBE_W12_CORRECTNESS
D1_NEXT_PREVIOUS_RESUME=FIXED          D1_REGRESSION_TESTS=PASS        PLAYBACK_CONTROLLER_UNIT_TESTS=18
D2_MID_SESSION_AUTHORIZATION=FIXED     D2_REGRESSION_TESTS=PASS
D3_DASHBOARD_SOURCE_MISMATCH=FIXED     D4_CHANNEL_SOURCE_MODEL=FIXED
D5_END_OF_VIDEO_ORACLE=FIXED           D5_NEGATIVE_TEST=PASS
D6_DPAD_HANDLER=REMOVED                D7_REJECTED_PLAYER_SURFACE=DISPROVEN
RENAMED_ITEM_CONTINUE_WATCHING=FIXED   REMOVED_VIDEO_RESUME_CLEANUP=DOCUMENTED
PLAYLIST_CACHE_CONSISTENCY=VERIFIED    SECURITY_INVARIANT=PASS
PROCESS_RESTART_RESUME=PARTIAL         (the saved playhead survives the kill and is offered; the
                                        harness could not press the offer's Resume in time)
```

## D1 — the playhead was saved against the wrong video after NEXT

**Root cause.** `PlaybackController.currentVideoId` was assigned in exactly two places: `start()` set
it, and a *rejected* start cleared it. `next()` and `previous()` moved the queue and called
`prepare(queue[i].videoId)` without touching it, while `savePosition()` read it as the id to write:

```kotlin
private fun savePosition() {
    val videoId = currentVideoId                    // still the video the child moved *away* from
    ...
    db.playbackPositionDao().upsert(PlaybackPositionEntity(videoId, position, duration, now))
}
```

So after any NEXT or autoplay advance, the new video's playhead was written into the previous video's
resume row (and read back later as that video's resume point). The same stale id fed `retry()`, the
DASH-failure fallback, the quality reopens and the "Start over" delete.

**Fix.** `currentVideoId` is now set in `prepare()`, the one function every start *and* every move goes
through — `start()`, `next()`, `previous()`, `retry()`, a quality reopen, the DASH fallback. Six lines,
one place, no behaviour change other than the id being right.

**Regression tests.** `PlaybackControllerTest` (new, 18 tests). With the fix reverted, 9 of the 18 fail,
including exactly the D1 symptom (`expected vid-b saved at 20000ms but it is null` — B's playhead landed
in A's row). With it, 18/18 pass in ~8 seconds.

## PlaybackController test foundation

The class had no tests at all. It now has a suite that runs the *real* controller over a real Room
database, the real `PlaybackAuthorization` and the real save path, with two things injected and nothing
else changed: the player (a stand-in whose playhead and state the test controls) and the resolver (so it
can be failed on purpose). The stand-in is an `ExoPlayer` proxy that answers the handful of calls the
controller makes and the zero of its return type for everything else, so no test fails over a method
nobody meant to exercise.

Covered: initial playback and the current video; NEXT; PREVIOUS (including the first-item restart);
independent playheads for three videos; progress save and restore; the resume floor and the >=95%
start-over rule; queue boundaries (last item ends the queue, nothing else is prepared); the end of a
video through the player's own `STATE_ENDED`; Continue Watching's query seeing the row that was actually
written; authorization refusing before anything is resolved; a resolver failure; and the D2 scenarios
below.

## D2 — authorization at a queue transition

A queue is a snapshot, and a parent can withdraw a source while a child is watching. The invariant W12
chose, implemented and tested: **an item must still be authorized when playback transitions to it**; a
revoked video must never play merely because it was in a list built earlier. `next()` and `previous()`
re-read `PlaybackAuthorization.approvedQueue(db, sourceId)` before they move, so a withdrawn video is
simply not in the queue any more and the item after it becomes reachable. Re-reading can only *shrink*
the queue, and the gate itself is untouched.

The already-playing video is not interrupted (there is no revocation watcher, and W12 did not add one);
the withdrawn item is never prepared, never resolved and never written to `playback_positions`, so it
cannot appear in Continue Watching. Written up in `SECURITY_MODEL.md` under "Authorization at a queue
transition".

## D3 + D4 — the source model

The disagreement was in the data model, not in either screen. A catalog **video** node's
`youtubePlaylistId` records where the video came from, and SafeTube can allow three kinds of source — a
playlist, a whole channel, or a single video — but the validator checked that field as a *playlist* id
and refused a channel id, while the dashboard built its allowed-source map from playlists only. So a
channel-sourced video could not be described in the file, and was reported unplayable while the TV
played it.

* `CatalogPayloadValidator` now checks a **video** node's field against all three kinds
  (`ContentSourceParser.sourceIdProblem`, which is the same round-trip rule as before applied to each
  kind). A **container** still names a playlist and nothing else, because that is what it imports.
* `app.js`'s `allowedSourceMaps` holds every allowed source, and `canPlay`/`sourceNameFor` consult it,
  so the library screen and the import preview agree with the TV.
* The fixture writes the Bluey channel into its own file again, and the loader reads its sources from
  the file alone.

Measured in a real browser against the same library: **"5 videos can't play yet, allow these 4 sources"
before, "1 video can't play yet" after** — and the one left is the fixture's deliberately unapproved
item. Measured on the device: the channel-bearing catalog document is accepted (`PUT /catalog` 200,
version 9, 46 nodes).

## D5 — the end-of-video oracle

The old assertion recorded a pass for either "playback stopped" or "advanced", so a queue that silently
died read exactly like a queue that finished. It now asks three separate questions and calls the queue
finished only when all three agree: nothing is playing, the app logged *"Approved queue finished"*, and
the player screen is gone (home *or* the container the playlist was opened from).

* `full` tier: `end-of-video-handling` now fails when playback stopped without the app reporting the
  queue finishing.
* `example` tier: `EXAMPLE_LIBRARY_PLAYLIST_FINAL_ITEM` uses the oracle after walking all 17 items, and
  `EXAMPLE_LIBRARY_QUEUE_END_ORACLE_NEGATIVE` asks the *same* oracle mid-queue, where it must **not**
  answer ENDED. A test that cannot detect a known failure mode is not verification; this one is asked
  both ways in the same run.

## D6 — the dead D-pad handler

`DpadKeyHandler` was referenced by nothing in `src/main`, while its test asserted a mapping that
contradicted the live one (it expected RIGHT and LEFT to be unhandled; the player seeks on both). Both
the object and the misleading test are **removed**, and `PlaybackKeysTest` pins the mapping the app
actually uses. The remote keys themselves are exercised on the device by the example tier, which drives
everything with key events only.

## D7 — the rejected player surface: disproven

W11 left it as a lead from reading the source (the rejected branch never calls `stop()` or
`clearMediaItems()`, and the `PlayerView` is always composed). Asked on the device as state, after
pressing the unapproved card: nothing plays; the refusal is on screen; the title of the last video that
played is **not** anywhere on screen (`EXAMPLE_LIBRARY_REJECTED_NO_STALE_TITLE`); BACK leaves the
library on screen; focus returns to a card that is really there; and Continue Watching gains no row for
the video that never played. All five pass. It is reported **DISPROVEN for the observable state** — the
pixel-level claim (a frozen frame behind the overlay) is not measurable with uiautomator, and `TESTING.md`
already records that the video surface returns no text to a dump.

## Catalog consistency

* **A renamed video now shows the catalog's name in Continue Watching.** The shelf's title came from the
  approval cache (whatever YouTube called the video) while every other shelf showed the parent's words,
  so a rename was invisible in exactly one place. The video id remains the identity — it keys the
  position row and the card — and the name follows the catalog when the catalog has one.
  `CatalogUiProjectionTest.aRenamedVideoIsListedUnderTheNameTheCatalogGivesItNow` pins it, and
  `CatalogHomeFlowTest`'s hand-built-container test was updated deliberately (it had pinned the old
  behaviour).
* **A removed video keeps its position, and only loses its card.** The lifecycle is now written down and
  tested: a saved position belongs to the approved cache, not to the catalog, so taking a video out of
  the library hides the card and nothing else; the row is deleted when the video is finished, on "Start
  over", and by the destructive reset. Re-publishing a video offers the child their old place, and the
  row is keyed by video id, so a video that comes and goes has one row rather than one per appearance.

## Playlist/cache consistency

Pinned as a security property rather than a layout one
(`CatalogNodeRepositoryIngestTest.everyVideoAContainerGrowsToIsStillIndividuallyApproved`): a container
backed by an approved playlist is filled from the **approved cache**, so it can list a video the catalog
document never named — the fixture's ChuChu shelf names 3 and shows 200. That is not an exposure, because
a video is in that cache *because* the parent allowed its source, and every one of them still passes
`PlaybackAuthorization`; the same test then withdraws the source and shows every one of those videos
refused while the tree still lists them.

## Verification

| what | result |
|---|---|
| `gradlew test` | **930/930 debug and 930/930 release**, 64 classes each, 0 failures |
| dashboard suites | **213/213** across 5 suites (editor 73, import 29, ui 50, yaml 36, parent-access 25) |
| `PlaybackControllerTest` | 18/18, and 9/18 fail with the fixes reverted |
| dashboard in a browser | 5 unplayable videos → 1, after the source-model fix |
| device, attempt 1 (`2026-09-28-185733`) | 31 checks, 3 failed |
| device, attempt 2 (`2026-09-28-191516`) | 31 checks, 2 failed |
| device, attempt 3 (`2026-09-28-193221`) | **31 checks, 1 failed** |

### What each attempt proved, and the one step the harness could not finish

Every check of D3, D5, D7, the playlist walk, the security refusal, the hidden item, the empty shelf,
the category order, the four-source playback (attempt 3 leaves it passing) and the restart itself
passes. The single remaining failure is the *last* step of the restart/resume sequence:

```text
saved before the kill   35s of 185s, then BACK           PASS (attempts 1-3)
process killed          gone, then running again          PASS (attempts 1-3)
Continue Watching       offers that exact video id        PASS (attempts 1-3)
the offer is on screen  "Resume" / "Start over" shown     PASS (attempts 2-3)
choosing Resume         playhead jumps to the saved point FAIL (harness)
```

W11's claim was that nothing restarted a process holding a saved position; that claim is now
disproven — the position is written before the kill, survives it, and the app offers to resume it
afterwards, on the real device, three times. What the harness does not do is press the offer's
*Resume* choice in time: the offer withdraws itself on its own idle timer, and this harness's dump-then-
press sequence spends that window, so its CENTER lands on the player instead. Reported as a harness
limitation rather than rounded up: the choice-application step is covered by the `full` tier's
`resume-choice-applied` / `resume-continues-position` checks (which drive the offer successfully from a
different state) and by `PlaybackControllerTest`'s resume tests, and the fix is to press the choice
before taking the dump that proves it is there.


## What is still not covered

* `connectedDebugAndroidTest` uninstalls the app and wipes the fixture; it was run with the reload
  protocol and the fixture was restored and re-verified afterwards.
* The player tier was not re-run in this phase; its last recorded result stands at the W10.1 commit.
* Two device-navigation steps in the example tier are inherently remote-driving (finding a long,
  emoji-bearing card title by its on-screen label) and have been made tolerant where they were flaky;
  a failure there is a navigation failure, and the phase now says so in its own message rather than
  reporting it as a product result.
