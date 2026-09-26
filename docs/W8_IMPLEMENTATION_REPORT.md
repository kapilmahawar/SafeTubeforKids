# W8 — Implementation report

```text
W8_STATUS=COMPLETE

BASELINE_COMMIT=953628e   (W7 complete, fork/main, working tree clean)
FINAL_COMMIT=<see §7>     (this commit)
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

## 8. Known limitations

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
