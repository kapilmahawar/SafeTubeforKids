# W11 — gap analysis and the deterministic example library

* Baseline: `eeb367a798854892059b521e500c6dcdeccad209` (`W10.1-STABLE-2026-09-28`), worktree clean.
* Date: 2026-09-28. Device: Mi Box 4 (`MIBOX4`, Android 12, 1920×1080 @ 320 dpi), ADB `172.16.1.2:5555`.
* No application source was changed in this phase. What changed is a test fixture, the harness that
  drives it, and documentation.

```
SAFE_TUBE_W11_GAP_ANALYSIS
{
  "phase": "W11",
  "start_commit": "eeb367a798854892059b521e500c6dcdeccad209",
  "end_commit": "see the commit that adds this file",
  "worktree_at_start": "CLEAN",
  "tags_untouched": ["W10.1-STABLE-2026-09-28", "W10-STABLE-2026-09-28", "W9-STABLE-2026-09-27", "W8-STABLE-2026-09-27"],
  "source_changes": 0,

  "A1_COMPLETE": [
    "catalog model: one flat tree (CATEGORY/SUBCATEGORY/VIDEO), parentId + position, canonical 0..n-1",
    "catalog document: schemaVersion 2, GET/PUT /catalog behind the parent session, 409 on a stale expectedCatalogVersion, whole-tree validation before any write",
    "catalog file: the YAML v1 model, its parser/emitter and the dashboard Library file panel (import preview, export)",
    "delivery: server document (catalog.json + catalog.version) -> TV Room mirror via CatalogSyncService, POST /catalog/refresh, hidden nodes filtered, empty categories not drawn",
    "parent access (W10): PIN verifier, recovery code, sessions, TV-only destructive reset",
    "playback authorization: one choke point (PlaybackScreen -> PlaybackController.boot -> PlaybackAuthorization), catalog membership grants nothing",
    "player: play, pause, seek, NEXT/PREVIOUS, approved queue, resume offer, start over, Continue Watching, focus restore",
    "branding: no obsolete upstream text in the app UI or in any source string"
  ],

  "A2_IMPLEMENTED_NOT_VERIFIED": [
    "end-of-video handling: the only device assertion passes on stop OR advance (tv-e2e.ps1:1797, :1799), so a silently dead queue passes",
    "process-restart coherence: positions persist in Room, but every test uses an in-memory database and no test restarts a process holding a saved position",
    "resume restart-from-zero at >=95%: implemented (PlaybackController.kt:343-346), never exercised (the harness only ever reaches ~40 s)",
    "Now Playing indicator: the data layer is tested for real; the TV top bar and the dashboard card are asserted by source-text regex only",
    "catalog changes while progress exists: strong unit/real-Room coverage, zero device coverage",
    "focus restore after BACK from the player: implemented; asserted for the first time by the W11 tier",
    "Continue Watching on a device: implemented; asserted for the first time by the W11 tier"
  ],

  "A3_DEFECTS": [
    {"id": "D1", "what": "next()/previous() never update currentVideoId, so every later use of it is the previous video: the periodic and exit-time save writes the new video's playhead into the old video's resume row, and retry(), the DASH fallback reopen, the quality reopens and the 'Start over' delete all act on the wrong id", "evidence": "PlaybackController.kt:256 sets it, :270 clears it, :445-474 never touch it, :441/:667/:673/:776/:805/:846/:860/:901/:938 read it", "status": "STILL_PRESENT", "verified_by": "read the source myself"},
    {"id": "D2", "what": "revoking an allowed source mid-session does not stop the rest of the queue: next()/previous() walk the queue snapshot taken at boot and never re-authorize", "evidence": "queue built at PlaybackController.kt:276; next() :445-458 has no authorize call; PlaybackAuthorizationTest:184 asserts the database property, not the controller", "status": "STILL_PRESENT", "verified_by": "read the source myself", "bounded_by": "nothing never-approved can enter the snapshot, so this is a TOCTOU on revocation, not an approval bypass"},
    {"id": "D3", "what": "the dashboard reports channel-sourced videos as unplayable even when the channel is an allowed source", "evidence": "live browser check: the library says '5 videos can't play yet ... Allow these 4 sources' while the TV plays 4 of those 5 (the three Bluey videos and the Mixed Cartoons copy). Cause: app.js isPlayable consults allowed.playlists (yt_playlist sources only, app.js:1210-1225) against node.youtubePlaylistId", "status": "STILL_PRESENT", "verified_by": "headless Chrome against the real dashboard + the device run"},
    {"id": "D4", "what": "a catalog file cannot say which channel approves a video: youtubePlaylistId is validated as a playlist id, so a UC... channel id is refused", "evidence": "PUT /catalog returned 400 'nodes[8].youtubePlaylistId: Could not find a video, playlist, or channel in this YouTube URL' (CatalogPayloadValidator.youtubeProblems -> ContentSourceParser.playlistIdProblem)", "status": "STILL_PRESENT", "verified_by": "the refusal itself, while loading the fixture"},
    {"id": "D5", "what": "the end-of-video assertion cannot distinguish correct queue handling from a dead queue", "evidence": "tv-e2e.ps1:1797 records PASS for 'queue ended', :1799 records PASS for 'advanced'", "status": "STILL_PRESENT", "verified_by": "read the harness myself; the W11 tier adds a bounded counter-example by walking a 17-item queue to its end"},
    {"id": "D6", "what": "DpadKeyHandler is dead code in main while DpadKeyHandlerTest asserts its mapping; the live key map (PlaybackKeys) is untested", "evidence": "the only occurrence of DpadKeyHandler in src/main is its own file", "status": "STILL_PRESENT", "verified_by": "grep myself"},
    {"id": "D7", "what": "the rejected-playback path never stops or clears the player surface, so the error overlay is drawn over whatever surface the player last had", "evidence": "TvPlayerScreen.kt:296-302 always composes the AndroidView/PlayerView; the overlay is drawn when errorMessage != null (:397)", "status": "UNVERIFIED_LEAD", "verified_by": "read the source; the visual consequence was not measured"}
  ],

  "A4_PRODUCT_GAPS": [
    "a renamed catalog node keeps its old title in Continue Watching, because the card's title comes from the approved cache, not the catalog",
    "resume positions are never purged for permanently removed videos: a re-added identical YouTube id resurrects the old position, even if that id is now a different video",
    "a playlist-backed collection is a floor, not a ceiling: the TV adds the rest of the approved cache to the container (the fixture lists 3 ChuChu items and the container shows 200)",
    "playlist contents are live, so a fixture can pin ids and order-at-build-time but not membership forever; the fixture is regenerated rather than repaired",
    "region-restricted videos surface as \"Couldn't play this video\" (a resolution failure, distinct from the permission refusal), and that path has no automated coverage",
    "there is no 'play this playlist from the start' affordance for a container; the child presses its first card (CatalogRepository.firstApprovedVideoOf has no production caller)"
  ],

  "A5_SECURITY": {
    "invariant": "CATALOG != PLAYBACK AUTHORIZATION",
    "holds": true,
    "entry_paths_checked": [
      "video card on the home screen - authorizes",
      "video card inside a container - authorizes",
      "a playlist/sub-category card - opens a container, starts nothing",
      "Continue Watching card - authorizes (and the query itself joins through the approved tables)",
      "resume of a partially watched video - authorizes on entry, then only seeks",
      "NEXT/PREVIOUS/autoplay - walk an already-authorized snapshot; see D2",
      "internal re-prepare (retry, DASH fallback, quality, audio, stall recovery) - reuse the authorized id; see D1",
      "deep links, ACTION_VIEW, custom schemes, app links - no such intent filter exists; the harness asserts a deep link plays nothing",
      "launcher/recents/task restore - re-enters the player route, which authorizes again",
      "debug broadcast receiver - authorizes, but in debug builds it can GRANT approval without a parent session (debug-only, stripped from release)",
      "relay / remote - forwards HTTP only; no remote-play command exists",
      "notifications, media session, media buttons - no MediaSession, no PendingIntent, media3-session is not a dependency",
      "the embedded server - no route anywhere accepts a video id to play"
    ],
    "verified_live": "an unapproved video that IS in the catalog plays nothing and logs 'Playback rejected: This video is not approved'; the W11 tier asserts it from the child's own card (EXAMPLE_LIBRARY_UNAPPROVED_DENIED, EXAMPLE_LIBRARY_UNAPPROVED_MESSAGE)"
  },

  "EXAMPLE_LIBRARY_LOAD": "PASS",
  "EXAMPLE_LIBRARY_NAVIGATION": "PASS",
  "EXAMPLE_LIBRARY_PLAYBACK": "PASS",
  "EXAMPLE_LIBRARY_SECURITY": "PASS",
  "ADB_TESTS": {
    "tier": "example (new)",
    "run": "test-results/tv/2026-09-28-162202",
    "result": "22 of 22 checks PASS",
    "checks": [
      "EXAMPLE_LIBRARY_LOAD", "EXAMPLE_LIBRARY_CATALOG_DOCUMENT", "EXAMPLE_LIBRARY_ROWS",
      "EXAMPLE_LIBRARY_CATEGORY_ORDER", "EXAMPLE_LIBRARY_SHELF_ORDER", "EXAMPLE_LIBRARY_EMPTY_SHELF_NOT_DRAWN",
      "EXAMPLE_LIBRARY_HIDDEN_ITEM", "EXAMPLE_LIBRARY_DPAD_NAVIGATION", "EXAMPLE_LIBRARY_PLAYBACK_FOUR_SOURCES",
      "EXAMPLE_LIBRARY_PLAYER_CONTROLS", "EXAMPLE_LIBRARY_BACK_LEAVES_PLAYER", "EXAMPLE_LIBRARY_FOCUS_RESTORED",
      "EXAMPLE_LIBRARY_CONTINUE_WATCHING", "EXAMPLE_LIBRARY_PLAYLIST_OPEN", "EXAMPLE_LIBRARY_PLAYLIST_FIRST_ITEM",
      "EXAMPLE_LIBRARY_PLAYLIST_ADVANCE", "EXAMPLE_LIBRARY_PLAYLIST_FINAL_ITEM",
      "EXAMPLE_LIBRARY_UNAPPROVED_DENIED", "EXAMPLE_LIBRARY_UNAPPROVED_MESSAGE"
    ],
    "earlier_runs_in_this_phase": [
      "2026-09-28-155854: 21/22 - the harness compared a one-item shelf against the letter 'L' (a PowerShell one-element array collapsed on return)",
      "2026-09-28-161034: 21/22 - EXAMPLE_LIBRARY_PLAYER_CONTROLS assumed one CENTER press toggles pause; the first press reveals the on-screen controls"
    ]
  },
  "UNIT_TESTS": {"command": "gradlew test", "debug": "910/910 PASS (64 classes)", "release": "910/910 PASS (64 classes)", "failures": 0, "skipped": 0},
  "DASHBOARD_TESTS": {"suites": 5, "passed": 211, "failed": 0, "detail": "editor 73, import 29, ui 48, yaml 36, parent-access 25", "node_check": "clean on app.js, theme.js, catalog-editor.js, catalog-yaml.js, parent-access.js"},
  "PLAYER_TESTS": {"last_run": "test-results/tv/2026-09-28-102139", "result": "41/41 PASS", "commit": "eeb367a", "note": "not re-run in W11; the player tier is untouched by this phase"},
  "FULL_TESTS": {"last_run": "2026-09-28 (W10.1)", "result": "53/53 PASS", "commit": "eeb367a", "note": "not re-run in W11; the full tier is untouched by this phase"},
  "INSTRUMENTED_TESTS": {"result": "NOT_RUN", "reason": "connectedDebugAndroidTest uninstalls the app and wipes catalog.json and the Room database, which would destroy the fixture and the approved sources this phase is about; 3 androidTest source files exist"},

  "RECOMMENDED_NEXT_PHASE": [
    "W12-1 fix D1 (set currentVideoId wherever the queue moves) and give PlaybackController its first unit test class, with a test that a save after NEXT writes the new video's row",
    "W12-2 decide D2: re-authorize (or re-read the queue) on each advance, and test that removing a source mid-session stops the queue",
    "W12-3 fix D3/D4 together: either let a catalog node name a channel source, or make the dashboard's playability check consult the same cache the TV uses",
    "W12-4 close the two untested branches the audit found: restart-with-a-saved-position (force-stop and relaunch) and the >=95% restart-from-zero",
    "W12-5 give the resolver-failure path a test, so \"Couldn't play this video\" and the rate-limit message cannot regress silently",
    "W12-6 make the end-of-video assertion able to fail (D5): assert the queue's own end rather than 'stopped or advanced'"
  ]
}
```

## What is complete

The catalog is complete as a product: a parent can build a library in the dashboard, export it as a
readable file, edit it, import it back with a preview, and the TV mirrors it and renders it with hidden
items hidden and empty shelves dropped. Parent access is complete. Playback authorization is a single
choke point and the invariant holds: I traced every way playback can start and none of them bypass
`PlaybackAuthorization`, and the device confirms an unapproved video that is *in the library* plays
nothing.

## What is missing

Three things, in order of how much they matter:

1. **`PlaybackController` has no unit tests at all.** Everything about the queue, the resume point and
   the end of a video is asserted only through the device harness, which is why a defect as direct as
   D1 (the resume point is saved under the wrong video id after every NEXT) has been able to sit there.
2. **Authorization is checked once, at entry.** A source revoked while a child is watching does not
   stop the rest of the queue (D2).
3. **The dashboard and the TV disagree about what can play** (D3), because a catalog file cannot name a
   channel as a video's source (D4). The TV is right; the dashboard is wrong, and it tells a parent to
   allow sources that are already allowed.

## What was verified, and how

| area | how | result |
|---|---|---|
| unit tests | `gradlew test` (debug + release) | 910/910 each, 64 classes each |
| dashboard | 5 Node suites + `node --check` on the 5 assets | 211/211, syntax clean |
| the fixture, on a real TV | `tv-e2e.ps1 -Tier example -SkipBuild`, remote keys only | **22/22 PASS** (`2026-09-28-162202`) |
| playback from four official sources | CoComelon playlist, Bluey channel, Peppa playlist, ChuChu playlist | all four played, each from an allowed source |
| a playlist end to end | 17-item official playlist, NEXT × 16 then one more | queue walked in the file's order, the last NEXT ended the queue and stayed in the app |
| the security boundary | the fixture's deliberately unapproved card | nothing played, the refusal was on screen |
| the dashboard's playability claim | headless Chrome, signed in with the PIN | it says 5 videos cannot play; the TV plays 4 of them |
| instrumented tests | — | **not run**: they uninstall the app and wipe the data this phase exists to check |

## Remaining defects

D1 through D6 above are all still present. None of them can let a child watch something unapproved;
D1 and D2 are correctness and revocation-timing defects, D3/D4 are a parent-facing lie about what can
play, D5 is a test that cannot fail, D6 is dead code with a live test. D7 is a lead I read in the
source but did not measure on screen.

## The fixture

Seven categories in order — CoComelon, Bluey, Peppa Pig, ChuChu TV, Mixed Cartoons, and two labelled
edge shelves (one video, empty). 46 nodes: 7 categories, 4 collections, 35 videos, 1 hidden. Five
official sources, all resolved through the app's own YouTube resolver when the fixture was built, and
all pinned by id in the file:

| source | id | items |
|---|---|---|
| CoComelon - Nursery Rhymes | `PLb8WrhcvGhOjFm2xrfaUZq3ytKuWQL5wE` | 105 |
| Bluey - Official Channel | `UCVzLLZkDuFGAE2BGdBuBNBg` | 200 cached |
| Peppa Pig - Official Channel | `PLFEgnf4tmQe-SPm9PXEZSKldOCzzBVby9` | 17 |
| Peppa Pig - Official Channel | `PLFEgnf4tmQe8dyOm2mZ8fSrZnwwIdoTAG` | 21 |
| ChuChu TV Nursery Rhymes & Kids Songs | `PLV-cxl3VSwWHa83pPHIealm1vg0Cdp3aZ` | 269 (200 cached) |

Every item, its YouTube id, its source, its position, whether the child sees it and what it is
expected to do is in `tv-app/scripts/fixtures/example-kids-library.json`, and the human-readable
version is [`EXAMPLE_KIDS_LIBRARY.md`](EXAMPLE_KIDS_LIBRARY.md).

## How to reset and reload it

```
node tv-app/scripts/fixtures/load-example-library.js --host <tv-ip> --pin <parent-pin> --replace-sources
```

Reloading is idempotent: it re-allows what is already allowed, replaces the catalog in one write,
refreshes the TV, and waits until the TV reports the version the server stored. `--replace-sources`
also removes whatever is not part of the fixture, which is what makes a run deterministic. A parent
does the same thing by hand in *Settings → Library file*. To start from nothing, use the TV's own
destructive reset, which no HTTP route can trigger.
