# W8 — Implementation report

```text
W8_STATUS=COMPLETE

BASELINE_COMMIT=953628e   (W7 complete, fork/main, working tree clean)
FINAL_COMMIT=1820c0c      (the W8 implementation; the two docs commits that follow are fb58c90 and
                           the report commit, ending at a7e62f1 + the commit that pins this line)
BRANCH=main
WORKTREE=fork/main, pushed to github.com/kapilmahawar/SafeTubeforKids

UX_AUDIT=docs/W8_UX_AUDIT.md   (15 findings, each CURRENT/PROBLEM/PROPOSED/WHY/RISK, plus the
                                terminology decision table)
SCREENSHOTS=docs/screenshots/w8/   (before/after pairs, curated; the full measured set of 25
                                    before + 25 after + 7 scenario captures is in %TEMP%\w8)

LIBRARY_UX=One summary line with real counts, category cards on a surface, the primary action
           first, the home-screen hint last, and the duplicate footer action removed.
TERMINOLOGY=Shelf→Category, Folder→Collection, Add from YouTube→Add to library,
            Allowed sources→Allowed YouTube sources, Send to TV now→Update the TV now,
            Reload from the TV→Reload the library, Check my library→Check for problems,
            Delete→Remove from library. Recorded in the audit; code identifiers unchanged.
COLLECTION_UX=A collection no longer offers to hold another collection; both screens carry their
              own menu, counts, artwork and one-tap reorder arrows.
ADD_FLOW=Paste → Continue → "What did you find?" (artwork, title, kind, count) → "Where should it
         go?" → "Let my child watch it" → Add to library. No technical field is ever shown, and an
         empty playlist, a channel link and an unusable link each get their own sentence.
AUTHORIZATION_UX=A hidden video is no longer counted as a problem; a visible unplayable video keeps
                 a "Can't play yet" badge, a sentence saying why, and one button to allow its
                 source; the item menu states "Ready to watch" when it can play.
REORDER_UX=One-tap ▲▼ on every row and card where order matters, plus Move up / Move down /
           Move to the top / Move to the bottom / Move to another category… in the menu. No
           drag-and-drop anywhere: reordering is deterministic and works with one hand.
SETTINGS=Grouped into TV, Time limits, Content, What has been watched, Appearance and
         Troubleshooting with h2 group headings over h3 card titles. Nothing was removed.
DARK_MODE=Same token architecture (theme.js before first paint, data-theme, localStorage). The
          active-section label and the breadcrumb links moved to a --brand-text token so text on a
          surface clears AA: the one pair below 4.5:1 (3.89:1) is gone.
LOADING_STATES=The shell is painted before the first request, so a cold load shows the header, the
               section bar and three skeleton rows instead of a blank page.
ERROR_STATES=Every server failure passes through one translator: the project's own parent-facing
             messages pass through, extractor/socket/JSON failures become a sentence a parent can
             act on. No raw exception can reach the screen, and a guard test enforces it.
ARTWORK=alt is the item's title, decoding is async, loading is lazy, the 16:9 box is reserved, and
        a picture that stops resolving is replaced by the same placeholder the rest of the UI uses.
ACCESSIBILITY=Heading order h1→h2→h3, labelled icon buttons, aria-current on the section bar,
              aria-pressed on the theme control, role=status toasts, visible focus everywhere,
              12 sampled text/background pairs all ≥ 4.5:1 (lowest 5.53:1).
MOBILE=360/390/412 px: no horizontal scrolling, no control under 40 px, every button named, no
       broken picture, dialogs fit, nothing hover-dependent.
DESKTOP=1280/1440 px: same mental model, category cards flow into a grid, no sidebar, no table,
        no admin panel.

BACKEND_CHANGES=NONE. The two W7 endpoints (/catalog/artwork, /catalog/refresh) and the resolver
                are unchanged; no route, status code or response shape moved.
DATABASE_CHANGES=NONE. Room v9, catalog_nodes, catalog_metadata, catalog.json are untouched.
SECURITY_CHANGES=NONE. PlaybackAuthorization, the allowed-source list and the catalog document
                 are unchanged. The one model change (renaming a video) touches a display string.

JVM_TESTS=846 / 846 debug, 846 / 846 release (unchanged from W7: no Kotlin was modified)
DASHBOARD_TESTS=128 / 128   (editor 73, import 29, ui 26; the ui suite gained 11 W8 guards)
MOBILE_TESTS=32 / 32 measured checks at 360/390/412/1280/1440 px, light and dark, 25 screenshots
             + 34 / 34 scenario checks driving all 25 steps of the brief through the real interface
ANDROID_TV_PLAYER=41 / 41
ANDROID_TV_FULL=56 / 56
```

---

## 1. Summary for a reader in a hurry

**What W7 already provided.** A dashboard that works: two sections (Library, Settings), real routes,
artwork from the TV, immediate publishing with a conflict dialog, an explicit light/dark control, and
no Apps/Kiosk surface. Everything structural was right, and W8 kept it.

**What W8 changed.** The interface became a consumer product rather than a correct tool. Concretely:

1. **A cold load is no longer a blank page.** The shell renders before the first request, with
   skeleton rows and "Loading what your child can watch…".
2. **The library says what it holds.** "2 categories · 1 collection · 52 videos", category cards with
   their own counts, the primary action first, the home-screen hint last.
3. **The vocabulary is a parent's.** Category, collection, allowed YouTube source, update the TV,
   remove from library.
4. **Reordering is visible and one tap.** ▲▼ on the row, plus top/bottom/another-category in the menu.
5. **Removing is safe and honest.** "Remove from library?" — and the sentence "It does not delete
   anything from YouTube."
6. **Authorization is understandable.** Unplayable-but-visible videos are explained and fixable;
   hidden ones are no longer nagged about; the menu confirms "Ready to watch".
7. **A collection no longer offers an impossible action** ("New folder" inside a collection).
8. **Errors are sentences, not stack traces** — one translator, enforced by a test.
9. **Settings are grouped**: TV, Time limits, Content, What has been watched, Appearance,
   Troubleshooting.
10. **The add flow has a shape**: paste → Continue → "What did you find?" → "Where should it go?" →
    "Let my child watch it" → Add to library.
11. **A video can be renamed** (PART 11): the display name is editable, the YouTube identity is not.
12. **Pictures carry names and degrade gracefully** (alt text, error fallback, reserved aspect ratio).
13. **One measured accessibility defect is fixed**: the active section label was 3.89:1 against the
    bar; text now uses a dedicated `--brand-text` token.

**Why.** Each of the fifteen findings in `docs/W8_UX_AUDIT.md` names the measurement that produced it
(computed styles, geometry, tap targets, contrast, the captured loading state, the dead "New folder"
control). No change was made because it seemed nicer; each one answers something that was measured.

**What was deliberately not changed.** The security model (`PlaybackAuthorization`, allowed sources,
catalog membership ≠ playback), the catalog document and its version precondition, Room v9, the sync
client and its ordering rule, the kiosk runtime and `AppsRoutes`, the theme architecture, the hash
routes, the action table, and the no-framework/no-build-step decision. Drag-and-drop was not added,
and the TV side was not touched at all.

**Backend, database, security.** No backend change. No database change. No security change.

## 2. The findings, and what happened to each

| # | Finding | Outcome |
|---|---|---|
| F1 | A cold load shows a blank page | **Fixed** — `render()` before the first `await`; skeletons + a labelled spinner |
| F2 | The library reads as a list of links | **Fixed** — counts summary, category cards, primary action first, hint last, duplicate footer action removed |
| F3 | The unplayable warning counts hidden videos | **Fixed** — only videos the child can reach are counted; a hidden one keeps its own badge |
| F4 | A collection offers "New folder", which always fails | **Fixed** — a new collection can only be made in a category |
| F5 | Partly the project's vocabulary | **Fixed** — the audit's decision table applied to 20+ strings |
| F6 | Reordering hidden in a menu | **Fixed** — ▲▼ on rows and cards, plus top/bottom in the menu |
| F7 | Deleting does not say what it does not do | **Fixed** — "Remove from library?", explicit "It does not delete anything from YouTube." |
| F8 | Settings is one long list | **Fixed** — six named groups, nothing removed |
| F9 | A raw extractor message can reach the parent | **Fixed** — one translator, one guard test, explicit states for empty/unusable/channel links |
| F10 | No alt text, no failure path for artwork | **Fixed** — alt is the title, error falls back to the placeholder |
| F11 | The add flow's verb is the implementation's | **Fixed** — "Continue", "What did you find?", "Where should it go?", "Add to library" |
| F12 | Nothing says when a video *is* ready | **Fixed** — the item menu states "Ready to watch", and allowing a source confirms it |
| F13 | Small consistency defects (weights, radii, surfaces) | **Fixed** — 700/600/400 only, one radius scale, one badge shape, `--brand-text` |
| F14 | Desktop is a stretched phone | **Fixed** — category cards flow into a grid at ≥760 px; lists stay single-column |
| F15 | Existing videos cannot be renamed | **Fixed** — the editor's refusal is gone; the video's identity is untouched (tested) |

## 3. The security boundary, restated and re-verified

Nothing in W8 weakens the rule the product is built on:

- `GET /catalog/artwork` and `POST /catalog/refresh` are read-mostly and were not modified.
- `PlaybackAuthorization` still reads only `channels` + `videos`; no Kotlin file was changed at all.
- Adding to the library still does not allow anything: the add screen's "Let my child watch it" is a
  visible switch, **off is a real choice**, and the scenario walk adds a video with it off and
  measures the result ("Can't play yet", plus a banner offering to allow the source). Allowing it
  afterwards is a separate, explicit request to `POST /playlists`.
- The only model change is renaming: the editor's `rename` now accepts a video, and the test proves
  that `id`, `youtubeVideoId`, `youtubePlaylistId`, `position`, `enabled`, `parentId` and
  `thumbnailMode` are all unchanged by it.
- No Apps/Kiosk/Installed-Apps surface returned; the guard test still fails the build if one does,
  and `AppsRoutes` and the kiosk runtime are untouched.

## 4. Measured evidence

Captured against the real app on the Mi Box over `adb forward`, in headless Chrome, with the TV's own
PIN. Full data in `%TEMP%\w8\after-metrics.json`, `after-contrast.json`, `after-checks.json`.

```text
widths            360 / 390 / 412 / 1280 / 1440 px, light and dark
checks            32 / 32 (audit) and 34 / 34 (the 25-step scenario)
horizontal        overflow 0 px at every width
tap targets       0 controls under 40 px (9 mobile, 7 desktop)
accessible names  0 buttons or links without one
pictures          0 broken; alt text is the item's title; every visible picture loaded
headings          h1 screen title → h2 settings group → h3 card title
contrast          12 sampled text/background pairs, all ≥ 4.5:1 (lowest 5.53:1 = the library
                  subtitle in light), plus the chrome measured separately: the section labels are
                  5.98:1 (light) / 7.79:1 (dark) inactive and 6.65:1 / 9.31:1 active, and white on
                  the primary button is 4.53:1. The one pair that used to fail (the active section
                  label at 3.89:1) now uses --brand-text and clears AA.
storage           session token, theme, one dismissal flag — never the library
endpoints         the same set as W7, plus nothing
```

The scenario walk (all 25 steps of PART 24) against the real TV, leaving the device as it found it:

```text
1-5    open the library, create a category (version 26 → 27), rename it, reorder it with the inline
       arrow (position 1 → 0), open it
6-9    resolve a real YouTube playlist ("CoComelon – Season 2", 50 videos), preview it, and see the
       destination default to where the parent came from
10-11  add one video from that playlist with "Let my child watch it" OFF → "Can't play yet"
12     the row shows artwork named for a screen reader, and the banner offers to allow the source
13     allowing it clears the warning on the same screen
14-17  a collection opens; the video is hidden (still in the library), shown again, then removed with
       a confirmation that says YouTube is untouched
18     the library is back to exactly the three nodes it started with
19-20  a reload shows the same library
21-23  dark mode chosen, reloaded, still dark and marked as the parent's choice
24     an unreachable TV says "Could not reach the TV. Is it on the same wifi?" — no jargon
25     a stale write is explained and nothing is overwritten
       the allowed sources are restored too, and the scenario's own approval is removed
```

## 5. Android TV regression

```text
PLAYER tier   41 / 41     (identical check list and result to the pre-W8 run)
FULL   tier   56 / 56     (identical check list and result to the pre-W8 run)
```

Every W6/W6.1 navigation check, the resume/Continue-Watching suite, the security pairs
(unapproved-video-blocked, api-refuses-unauth-read/write, deeplink-plays-nothing) and `no-app-crash`
all still pass: W8 changed no Kotlin and no TV-side behaviour.

## 6. An incident, reported rather than hidden

While verifying W7's instrumented asset test, `connectedDebugAndroidTest` finished by **uninstalling
the app**, which deleted its private data: the catalog document *and* the Room database (approved
sources, watch history, resume positions, time-limit configuration). The dashboard's own audit had
already been run against the wiped device before the loss was noticed, which is why the first "W8
before" screenshots showed an empty library; they were re-taken after recovery.

Recovered, from evidence captured earlier in W7:

- the catalog document: the three baseline nodes, byte-identical to the pre-W7 baseline
  (`%TEMP%\w7\before-document.json`), restored with `PUT /catalog`;
- the approved source: `PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT` re-allowed, which re-resolved to
  "CoComelon – Season 2" with 50 cached videos and artwork from `i.ytimg.com`.

Not recoverable: watch history (play events), resume positions, and any time-limit configuration —
those lived only in the deleted database, and they were already empty before the loss (the W7 phase
had recorded `totalEventsAllTime` growth from its own test playback, which is also gone).

The audit harness was hardened as a result: it re-reads the session before every request, records
the baseline document before touching anything, restores the library *and* the allowed sources on
both the success and the failure path, and writes its evidence as it goes.

## 7. Commits

```text
W8-0   docs(w8): audit the dashboard as a product
       docs/W8_UX_AUDIT.md, docs/screenshots/w8/ (before/after pairs)
W8-1   feat(web): polish the parent dashboard for an ordinary parent
       app.js, style.css, index.html (unchanged shell), catalog-editor.js, the three dashboard suites
W8-FINAL docs(w8): report the polish
       docs/W8_IMPLEMENTATION_REPORT.md, docs/TESTING.md (test counts), the curated screenshots
```

`FINAL_COMMIT` and the working-tree status are recorded in the delivery note that accompanies this
report. Every commit builds and passes the dashboard suites on its own.

## 8. W8.1 — live TV "Now Playing"

```text
W8_1_NOW_PLAYING_STATUS=COMPLETE

OLD_IMPLEMENTATION_FOUND=YES (git history, pre-W7 `assets/index.html:46-67` and `app.js:388-441`)
OLD_API=GET /status → currentlyPlaying {videoId, playlistId, title, playlistTitle, elapsedSec,
        positionSec, durationSec, playing} - the same shape it has today, unchanged
CURRENT_API=GET /status, the same endpoint; no new route, no new field, no new socket
TV_SOURCE_OF_TRUTH=PlayEventRecorder (in-memory, process-wide): the player publishes the playhead
                   every 500ms from Media3 itself, and clears the published state on stop, on
                   next/previous, on leaving the player and on an authorization rejection. The
                   status route reads that live, so it is what the TV is doing - not a log

NOW_PLAYING_UI=A card above "Your library": label, artwork, title, ▲Playing on TV / ❙❙ Paused on TV,
               position / duration and a progress bar, with Pause and Stop. A one-line variant for
               a TV that is idle, unreachable, connecting or has stopped reporting. The header pill
               carries the same answer on every other screen.
LIVE_UPDATE_METHOD=polling with a locally advanced playhead: the TV is asked every 5s while playing,
                    10s while paused and 20s otherwise, the playhead is interpolated every 1s from
                    the last report, and the card is painted in place (never re-rendered) so the
                    parent's scroll and focus survive. One status timer, retuned not duplicated,
                    silent while the page is hidden, with an immediate ask when it comes back.
REFRESH_INTERVAL=5000 / 10000 / 20000 ms (playing / paused / otherwise); playhead tick 1000 ms
STALE_STATE_HANDLING=the endpoint carries no timestamp, so freshness comes from the one thing that
                     must move while a video plays: the playhead. Three consecutive polls reporting
                     the same position while the TV says it is playing switches the card to "The TV
                     stopped reporting what it is playing." with a Try again button. A *new* video
                     id, a new position or a pause each reset that counter, so a real pause is never
                     mistaken for a dead TV, and a missed poll is never reported as "nothing is
                     playing".

PLAYING_TEST=PASS  the card appeared on its own, named "Wheels on the Bus", showed 0:09 / 3:49, the
                   picture loaded, and the position advanced (TV 10s→16s, card 0:09→0:14)
PAUSED_TEST=PASS   "❙❙ Paused on TV" and the position frozen across six seconds
RESUME_TEST=PASS   "▲Playing on TV" again, without a reload
SEEK_TEST=PASS     two remote seek presses: TV 24s→36s, card 0:23→0:47
VIDEO_TRANSITION_TEST=PASS  skip moved the TV to the next queue item and the card followed it
                            ("Wheels on the Bus" → "Hot Cross Buns", playing, 0:00 / 2:52)
STOP_TEST=PASS     the card collapsed to "Nothing is playing right now" and the pill stopped naming
                   a video
NETWORK_FAILURE_TEST=PASS  with /status blocked the card said "Unable to reach the TV." (not
                           "nothing is playing"), offered Try again, and recovered without a reload
MOBILE_TEST=PASS   360/390/412px: no sideways scrolling, the card is 239px of an ~800px viewport
                   (30%), both buttons are 40px
DARK_MODE_TEST=PASS  the card repaints (surface rgb(23,28,35), title rgb(238,241,246)); screenshots
                     in both themes, and at 1280px

SECURITY_IMPACT=NONE. The card is informational; it reuses the two playback controls the dashboard
                already had (POST /playback/pause, /playback/stop - session-authenticated, no body,
                acting only on what is already playing) and grants no new permission. Nothing here
                can start playback, name a video to play, or reach an external URL, and
                PlaybackAuthorization is untouched: a video reaches the player only through the
                catalog and the allowed-source list, exactly as before.
KNOWN_LIMITATIONS=1) There is no push channel: the TV is polled. A WebSocket or SSE would be a new
                  architectural component for a status card, so polling stayed. 2) The playhead is
                  interpolated between polls, so it can be a second ahead of the TV; every poll
                  corrects it. 3) The endpoint carries no timestamp, which is why staleness is
                  derived from the playhead - a TV that is *paused* by a time limit and a TV that
                  has died look the same to the client until the position stops moving for three
                  polls. 4) A video the library does not name has no artwork in the TV's cache, so
                  the card falls back to YouTube's thumbnail for the reported id, and to the
                  placeholder if that does not load. 5) Buffering shows as "playing" with a still
                  playhead, which is what Media3 is actually doing.
```

**What W8.1 was, in one line:** W7 removed only the *UI* — the card and its script. The server
endpoint (`GET /status`), the TV's playback reporting (`PlayEventRecorder` + `PlaybackController`) and
the debug broadcast that can start a video were all still there and unchanged, so W8.1 **reconnected
an existing data source to the new dashboard**: no new endpoint, no Android change, no database
change, and no second playback-state system. The old card fabricated its thumbnail URL and jumped its
progress bar every 30 seconds; this one asks the TV every five seconds, advances the playhead locally
in between, and distinguishes a paused TV from an unreachable one from a TV that has stopped
reporting.

Two defects were found by the device run and fixed before this commit:

1. the one-line states were never repainted, so a TV that became unreachable kept showing the
   reassuring "Nothing is playing right now" until the page was reloaded — the exact confusion
   PART 5 forbids;
2. the freshness counter disabled the local playhead interpolation on the *first* repeated reading,
   which is what surfaced the seek behaviour as a frozen clock.

## 9. Known limitations


1. **No drag-and-drop reordering**, deliberately (F6): ▲▼, "Move to the top", "Move to the bottom"
   and "Move to another category…" cover every journey, are deterministic, and work on a touch
   screen and by keyboard. The audit records the reasoning.
2. **A renamed video keeps its YouTube title in the catalog's provenance**: `youtubeVideoId` and
   `youtubePlaylistId` are untouched by design, so a re-import of the same playlist still reconciles
   the node by video id rather than by name. That is the intended identity rule.
3. **Artwork size is the TV's choice, not the page's.** The cache stores whatever the resolver gave
   (typically `hqdefault`), and the page cannot ask for a smaller rendition. Lazy loading, `async`
   decoding and a reserved 16:9 box are all it can do, and they are done.
4. **The relay was not re-tested.** It is a generic path proxy and serves the same bundle; W7's
   `theme.js` reasoning still holds, and the W8 changes are inside the same files.
5. **Time limits, watch history and resume positions on this device are now empty**, because of the
   uninstall described in §6. The functionality is unchanged and covered by tests; only this
   device's data is gone.
6. **The instrumented asset test must not be the last thing run before a device demo**, since it
   uninstalls the app. Noted in `docs/TESTING.md`.
