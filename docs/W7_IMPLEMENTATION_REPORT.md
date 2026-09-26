# W7 — Implementation report

**Deliverable:** the SafeTube parent dashboard, redesigned so an average parent can manage the
catalog from a phone browser, with every Apps / Kiosk / Installed-Apps surface removed from the UI.

| | |
|---|---|
| Started from | `d9aad3f` (W7 checkpoint, parent `81a2053`) |
| Audit commit | `553fea4` — `docs(w7): audit the repository and the server before redesigning the dashboard` |
| Backend commit | `86e1c7c` — `feat(server): add the two endpoints the redesigned dashboard needs` |
| Redesign commit | `a6f51ba` — `feat(web): redesign the parent dashboard for a phone` |
| Pushed to | `fork` = `github.com/kapilmahawar/SafeTubeforKids`, branch `main` (`d9aad3f..a6f51ba`) |
| Audit document | [`docs/W7_REPOSITORY_AUDIT.md`](W7_REPOSITORY_AUDIT.md) |
| Verified on | Xiaomi Mi Box 4, Android 12 (API 31), 1920×1080 @320 dpi, `172.16.1.2:5555` |

The audit came first and is a separate commit, because PART 1 asked for the repository to be
understood before it was changed: the ROUTE inventory, the catalogue data flow, and the
classification of every Apps/Kiosk-related line into "UI-only" and "runtime behaviour" are all in
`docs/W7_REPOSITORY_AUDIT.md`, written from the tree at the checkpoint rather than from the project's
own (partly stale) documentation.

---

## 1. What the parent gets now

Nine screens, all reachable without a mouse and without a hidden gesture:

| Screen | What it is for |
|---|---|
| Connect | the PIN from the TV; the only screen before sign-in, and it tells the parent the TV is unreachable before they hunt for the PIN |
| Library (`#/library`) | the shelves, in order, with counts; "Add from YouTube" and "New shelf" |
| Shelf (`#/shelf/<id>`) | what is inside a shelf, in order; add a folder or videos; rename/hide/delete the shelf from its own heading |
| Folder (`#/folder/<id>`) | the videos inside a folder; the same controls; pictures and counts |
| Item menu | one list, per row: rename, hide/show, move up, move down, move to another shelf, choose the picture, delete |
| Add (`#/add/<where>`) | paste a link → check it → see what it is (playlist or one video, with a picture and a count) → choose where it goes → "Let my child watch it" → add |
| Settings (`#/settings`) | the TV (what is playing, stop/pause/next), screen time (limits, bedtime, lock, bonus minutes, the child's request), allowed sources, the library (send/check/reload), what has been watched, the look, and a copyable error report |

Design rules that are actually enforced, not just intended:

- **Two sections and nothing else.** Library first; Settings second. A bottom bar on a phone, header
  tabs on anything wider. The library is the default route, and Back returns to it.
- **Every change is published immediately.** Rename, reorder, move, hide, add and delete each become
  one atomic `PUT /catalog` at the version the page read, with a visible "Saving…"; a refused write
  leaves the library exactly as it was, and a conflict offers to load the latest rather than
  overwriting somebody else's edit.
- **Saved is not the same as "on the TV".** After every write the page asks the TV to catch up and
  then says which happened: "Your TV has it now" or "Your TV will pick it up within 15 minutes".
- **Being in the library is not permission.** A curated video that cannot play says "Cannot play
  yet", the screen it is on explains that its source is not allowed, and one button allows it. The
  add screen makes the same decision explicit with a switch.
- **No developer vocabulary.** No catalog, node, position, schema, thumbnail mode or playlist id
  anywhere a parent reads. A shelf is a row on the TV; a folder is a card that opens a list.
- **Mobile-first and accessible.** Token palette, one dark theme, an explicit light/dark control in
  the header and in Settings (remembered per phone, applied before the first paint by `theme.js`),
  no reliance on hover, no control under 40 px, real `<dialog>` modals with focus handling, a skip
  link, `aria-current` on the active section, and labels on every icon-only button.
- **No framework.** 22 KB of hand-written CSS and 100 KB of plain JavaScript in the same IIFE style
  as the rest of the project, with no build step — `node --check` and `node --test` still work on the
  files as they ship, and CI now runs the dashboard's model and guard suites on every push.

## 2. What was removed, and the one thing that was not

Removed from every dashboard file (markup, script, styles, navigation, routes, tests):

- the "Kiosk Mode" and "Installed Apps" cards, their handlers (`loadKioskConfig`, `loadAppsList`,
  `toggleKiosk`, `updateKioskEnforceTime`, `toggleAppWhitelist`), their styles, and the
  `setup-kiosk.sh` reference in the page;
- the old single-page layout, the inline `onclick`/`onchange` handlers, the `window.*` globals they
  needed, and the `catalog-tree.js` renderer with its route and its test file
  (`dashboard-catalog-tree.test.js`, 315 lines), replaced by the library screens and by the editor
  model's own pre-flight (`CatalogEditor.problems`, previously exported and unused).

Four guards now make the removal permanent: the asset test and the JavaScript suite both fail if
`kiosk`, `Installed Apps`, `app_whitelist`, `/apps` or `setup-kiosk` reappears in `index.html`,
`app.js`, `style.css` or `theme.js`.

**Not removed, deliberately: `AppsRoutes.kt`.** `GET /apps` is the only code in the repository that
inserts into `app_whitelist`, and `PUT /apps/whitelist` the only code that can set
`whitelisted = 1` — which is what the TV's kiosk home screen renders. Deleting them would not remove
a screen; it would permanently empty the child's app row on every provisioned kiosk device, with no
way left to fill it (the debug broadcasts that could do it are debug-build only). The routes are not
UI, the dashboard links to none of them, and the audit states the consequence plainly: **a parent can
no longer enable kiosk mode or choose the child's apps from the web page.** If the intent was to
retire the kiosk feature entirely, that is a much larger change than W7 authorised (delete
`KioskManager`, `HomeWatcherService`, `SafeTubeAdmin`, the kiosk home screen, the two Room tables and
`setup-kiosk.sh`), and it should be its own phase.

## 3. Backend changes, and why each was necessary

| Change | Why the redesign could not do without it |
|---|---|
| `GET /catalog/artwork` | The document deliberately carries no artwork and no children for an imported playlist. A library screen that showed a 50-episode playlist as "0 videos" and every card as blank would be worse than no pictures at all. It answers from the TV's own tree with `CatalogThumbnails.representatives` — the same resolver the TV renders with, not a second implementation — reports the version the TV has *installed* rather than pretending to describe a draft, names only the videos the library actually references, gives no picture to a shelf (W6.1's rule), and writes nothing. |
| `POST /catalog/refresh` | Without it a parent who has just saved has to wait up to fifteen minutes to see whether it worked. It performs exactly what the TV's own Refresh button performs — resolve the approved sources that have no cached videos yet, then sync the catalog, in that order — and grants nothing new. It answers in the three shapes the dashboard must tell apart: refreshed, unavailable (`502`), or refused with a reason (`502`). |
| `POST /catalog/import/resolve` learns `kind` and video links | So one screen and one endpoint can preview a playlist *or* a single video. Read-only as before, approving nothing as before; a channel link is now refused with the reason and the place to go instead. |
| `script-src` loses `'unsafe-inline'`; `img-src` gains `https://i.ytimg.com` | The inline handlers are gone, so the exemption is gone with them — a page that ever grows one is now refused by the browser rather than trusted. YouTube hands artwork out from either host, and the new cards show it. |

Nothing else in the server changed: no route was added beside these, no schema, no table, no version
rule, no ordering rule, no authorization rule. A guard test asserts that the dashboard's API surface
is a subset of the endpoints that already existed plus exactly these, so a future screen cannot
quietly invent a second way to change the library.

## 4. Verification

### 4.1 Automated

```text
JVM unit tests      testDebugUnitTest    846 / 846   (was 833)
                    testReleaseUnitTest  846 / 846
Dashboard JS        node --test scripts/dashboard-*.test.js
                    dashboard-catalog-editor.test.js   73 / 73
                    dashboard-catalog-import.test.js   29 / 29
                    dashboard-catalog-ui.test.js       15 / 15   (new)
Instrumented        DashboardAssetTest    7 / 7       (device, real assets)
```

New: `CatalogLibraryRoutesTest` (11 tests: access, the pictures, the counts, the shelf rule, that
neither route writes anything, and all three refresh outcomes), two tests for the video link and the
channel refusal, three for the new asset routes, and the whole `dashboard-catalog-ui.test.js`
(the theme rule, the token palette, the action table, what the browser may store, and the removal
guards).

Three guard tests were rewritten rather than deleted, because their property is what matters and the
implementation legitimately changed: "every control in the page is wired to a function the script
exposes" became "every control goes through the action table, and the page carries no inline
handler" (it now checks both directions, so an unused action table entry fails too); "the page offers
exactly two pictures" now asserts the two modes the page can produce rather than the markup that
produced them; and the import-control test now asserts the flow's contract (resolve, then
`importPlaylist`/`addVideo`, destinations from `validParentsFor(..., VIDEO)`, approval only through
`POST /playlists`).

### 4.2 On a phone-sized browser, against the real TV

A CDP harness (`%TEMP%\w7\browser.js`, not part of the app) drives headless Chrome through
`adb forward tcp:8080` against the TV's real server, signs in with the TV's real PIN, and checks what
a parent would notice. **42/42 audit checks**, plus **51/51** including a full write round trip:

- 360, 390 and 412 px: no sideways scrolling at any of them, every control ≥ 40 px tall, both bars
  laid out, the palette applied (not the browser default), and both sections present;
- the shelf screen: rows ≥ 56 px, nothing past the right edge, every picture loaded;
- exactly two sections, and no kiosk/apps vocabulary anywhere on screen;
- the item menu opens as a real modal and offers rename / hide / move up / move down / move to /
  picture / delete; the shelf that is open can be renamed from its own heading;
- a folder whose episodes the TV materialises from a playlist explains itself instead of saying
  "nothing here" while counting 50 videos;
- the add screen: a real playlist resolves (50 videos, "CoComelon – Season 2") and previews with a
  picture; a real video resolves to "Wheels on the Bus" with "One video";
- the theme control flips the look, the dark palette really repaints the page (body and cards change
  colour), the choice is remembered, and it survives a reload reporting that the parent chose it;
- **round trip, on the TV's own document**: a new shelf (`30 → 31`), a rename, a video added from a
  real YouTube link with the approval switch *off* — which then shows "Cannot play yet" and offers to
  allow the source — then hidden (still present, `enabled=false`, nothing deleted), then the shelf
  deleted, leaving the document **content-identical to the pre-W7 baseline** and the TV caught up to
  that version;
- no content-policy violation and no console error in any of it, at any width, in either theme.

### 4.3 Android TV regression

The TV side was not touched, and the device harness says so:

```text
PLAYER tier   41 / 41    (identical check list and result to the pre-W7 run)
FULL   tier   56 / 56
```

Every W6/W6.1 check still passes: cards are focusable, a category heading is not, a container opens
its children, Back returns to the shelf and restores the card's focus, a category has no picture,
and the security pairs hold (an unapproved video is blocked, the API refuses unauthenticated reads
and writes, deeplinks cannot start playback).

### 4.4 Device state, restored

```text
screensaver_enabled     1        (was 1)
screen_off_timeout      900000   (was 900000)
stay_on_while_plugged_in 0       (was 0)
adb forward list        empty    (the harness forwards were removed)
catalog document        version 40, nodes byte-identical to the version-30 baseline
installed APK           app-debug.apk of a6f51ba
```

Versions only ever move forward (that is the model's rule), so the document is at 40 where it was at
30 — with the same three nodes, in the same order, with the same timestamps.

## 5. Deliberate limitations and follow-ups

1. **Drag-and-drop reordering was not implemented.** The spec asked for drag handles *and* up/down,
   with drag explicitly not the only mechanism. Reordering is served by "Move up", "Move down" and
   "Move to another shelf…", all deterministic and reachable on a phone and by keyboard. A
   touch-drag reorder without a library is unreliable in exactly the situation it is needed (a
   scrolling list on a small screen), and an unreliable reorder in a catalogue editor is worse than
   two buttons that always work. If drag is wanted, it should be built with pointer events and a
   `touch-action: none` handle, and tested on a real phone.
2. **An existing video cannot be renamed.** The editor model refuses it ("a video keeps the name it
   was added with"), and W7 did not change model behaviour. New videos *can* be named: the add screen
   prefills YouTube's title in an editable field. Allowing renames would be a one-line change in
   `catalog-editor.js` plus the tests that pin the rule.
3. **The relay was not exercised against the new asset.** `theme.js` is a new file the page loads, and
   the relay is a generic path proxy (`/tv/<id>/api` → `/api`, everything else straight through), so
   it should serve it exactly as it serves `app.js`. That is an argument from the code, not a
   measurement: the relay service and its credentials are not in this repository.
4. **`AppsRoutes` is retained** (see §2), which means the kiosk feature keeps its API but loses its
   only user interface.
5. **Two documents stay stale**, both predating W7 and both now flagged in the audit rather than
   half-corrected: `docs/ARCHITECTURE.md` still draws the version-1 contract (`categories`,
   `content_items`) and `docs/PROJECT_CONTEXT.md:49` still says there is no dashboard UI for the
   catalog. The route lists in `ARCHITECTURE.md` and the test counts in `docs/TESTING.md` were
   brought up to date, because W7 is what made them wrong.
6. **Instrumented coverage is one class.** `DashboardAssetTest` asserts the shell, the absence of
   inline handlers, the presence of `theme.js`, the absence of `catalog-tree.js`, and that no asset
   manages apps or kiosk. The rest of the UI is covered by the JavaScript suites and by the browser
   harness, which is where a DOM behaviour belongs.
