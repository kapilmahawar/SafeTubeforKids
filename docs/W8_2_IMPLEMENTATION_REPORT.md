# SafeTube W8.2 Implementation Report

## Baseline

```text
BASELINE=2b14b89e51664c7e1203dbe6bef345479f85c319   (W8.1, fork/main, worktree clean)
DEVICE=Xiaomi Mi Box 4 (MIBOX4), Android 12 / API 31, adb 172.16.1.2:5555
DASHBOARD=the TV's own Ktor server, reached through adb forward at http://127.0.0.1:8080
AUDIT=docs/W8_2_UX_AUDIT.md (unmodified; it remains the record of the pre-W8.2 state)
```

The audit found zero P0 defects and concluded that the dashboard works as a parent library
application. This phase therefore implements **only** the nine corrections it named: F1, F2, F3, F4,
F5, F6, F7, F8, F15. Nothing else was touched — F9–F14 and F16–F20 are deliberately not implemented,
and one of them (F12) was actively reverted when an edit of mine strayed into it.

## Scope

| Finding | One line | Status |
|---|---|---|
| F1 | The "back" breadcrumb was a 74×28px tap target | fixed |
| F2 | Removing a 50-video collection said it contained "0 items" | fixed |
| F3 | The channel refusal used the retired word "shelf" | fixed |
| F4 | A failed artwork read was reported as a stale TV | fixed |
| F5 | The library and the collection disagreed about how many videos it holds | fixed |
| F6 | The conflict dialog exposed a catalog version number | fixed |
| F7 | A row stated its state twice (meta line and badges) | fixed |
| F8 | A collection was described as "the video" in the move menu | fixed |
| F15 | An unknown link fell back to a generic error | fixed |
| F9 | Desktop 728px column | **DEFERRED** |
| F10–F14, F16–F20 | Settings labels, version placement, copy, icons, PIN pointer and the rest | **NOT IMPLEMENTED** |

Two further defects were found *by the live verification of these nine* and fixed inside their scope:
the collection count needed the TV-aware helper (F2), and the extractor's own "Got error ERROR: …"
text was passing the readability filter, so the F15 translation never ran (F15). Both are described
below.

## F1 — Breadcrumb Tap Target

**BEFORE** `.crumb` was `padding: 4px 2px` with 14px text: measured **74×28px** on every child screen
(`category-mobile`, `collection-mobile`, `add-content-mobile` in the audit) — the only control in the
whole audit below the project's 40px minimum, and the control a parent uses to go back.

**CHANGE** `style.css`, `.crumb` only:

```css
.crumb {
    display: inline-flex;
    align-items: center;
    min-height: 40px;
    padding: 0 4px;
    ...
    font-size: var(--fs-sm);   /* unchanged */
}
```

The text keeps its size; the target grows around it.

**WHY** A back control that is hard to hit is a usability defect on every child screen at once, and the
fix costs one rule.

**TEST** Measured live on the category screen at 360, 390, 412, 1280 and 1440 px in **both themes**
(`%TEMP%\w8_2\responsive.json`), checking height, sideways overflow and clipping.

**RESULT** **PASS** — 40×78px at every width and theme (was 28×74), crumbs row 40px, `overflow: 0`,
`clipped: false`; 10 measurements, 0 problems. Screenshots `f1-breadcrumb.png`,
`f1-breadcrumb-360-light.png`, `f1-breadcrumb-1280-light.png`, `f1-breadcrumb-1440-dark.png`.

## F2 — Collection Removal Consequence

**BEFORE** A collection headed "50 videos" offered `Remove from library — Removes this and the 0 items
inside it`, and the confirmation said only "This removes it from your child's SafeTube library…". The
count came from `descendantsOf(node.id).length` — the VIDEO rows in the document, which for an imported
playlist is zero, because its episodes live on the TV.

**CHANGE** `app.js`: a new `removableVideoCount(node)` — `0` for a video, `containerVideoCount(node)`
for a collection, `reachableVideoCount(node)` for a category — used by both the menu entry and the
confirmation, which now reads:

```text
Remove “CoComelon” from your library?
This removes it and the 50 videos inside it from your child's SafeTube library, on this phone and on
the TV. It does not delete anything from YouTube.
[Remove] [Cancel]
```

**WHY** This is the one dialog where a parent decides what their child loses, and it was wrong by a
factor of fifty. `containerVideoCount` already existed for exactly this (the TV's own count, which
knows about materialised episodes); the audit's recommendation was to reuse it rather than invent a
second count.

**TEST** Live, on the real 50-video collection: open the ⋯ menu, read the removal entry, open the
confirmation, read it, then Cancel (nothing is removed).

**RESULT** **PASS** — menu: `Removes this and the 50 videos inside it`; confirmation: `…and the 50
videos inside it from your child's SafeTube library…It does not delete anything from YouTube.`
Screenshots `f2-remove-menu.png`, `f2-remove-confirm.png`.

*Defect found by this test:* the first implementation used `reachableVideoCount(node)` for a
collection, which counts its *document* children and was therefore still zero. The TV-aware helper is
now used for containers, and a guard test asserts the distinction.

Zero and one still read correctly: `removable = 0` produces "Removes this from your library; nothing
is deleted from YouTube" and the confirmation drops the count clause; a single video produces
"It disappears from your child's library; nothing is deleted from YouTube".

## F3 — Channel Terminology

**BEFORE** Refusing a channel link answered `A whole channel is not a shelf. Allow that channel in
Settings, then add the videos or playlists from it that you want your child to see.` "Shelf" is the
pre-W8 word for what the interface now calls a **category**, so the parent met a term the product
never shows them.

**CHANGE** `CatalogImportRoutes.kt`, one string:

```text
A whole channel can't be added here. Allow that channel in Settings, then add the videos or
playlists you want from it.
```

**WHY** Terminology consistency in the one place a parent is already stuck. Nothing about the refusal
behaves differently: it is still a 400, still approves nothing, still points at Settings.

**TEST** Live: paste a channel link into the add screen and read the message. Plus the existing
`CatalogImportRoutesTest` assertion, updated to require the new wording and to assert that the word
"shelf" does not appear.

**RESULT** **PASS** — the dashboard shows the new sentence verbatim, no "shelf" anywhere; JVM
846/846 in both build types. Screenshot `f3-channel-error.png`.

## F4 — TV Freshness / Artwork Failure

**BEFORE** `loadArtwork()` recorded `installedCatalogVersion: 0` when the read failed, and
`screenLibrary` compared that zero with the document version:

```js
var behind = state.session.catalogVersion > 0 &&
    state.artwork.installedCatalogVersion < state.session.catalogVersion;
```

So a *picture* problem became a *stale TV* claim. The audit's isolated A/B test proved it: with only
`GET /catalog/artwork` blocked and the pill reading "TV on", the library said "Your TV does not have
the latest yet" and offered "Update the TV now".

**CHANGE** `app.js` — the state is classified once, in one place, and only one of the four states may
ever claim staleness:

```js
state.artworkKnown = true | false        // set by loadArtwork: did the read succeed at all?

function tvFreshness() {
    if (state.reachable === false) return 'unreachable';
    if (!state.artworkKnown) return 'unknown';        // a failed read is not evidence
    if (!state.session || state.session.catalogVersion <= 0) return 'unknown';
    return state.artwork.installedCatalogVersion >= state.session.catalogVersion ? 'current' : 'behind';
}
```

Both consumers now use it: the library banner is gated on `freshness === 'behind'`, and the Settings
library panel says "Your TV has this version." / "Your TV is on an older version — update it now." /
"What version your TV has could not be checked just now." for the unknown case.

**WHY** This is a correctness fix, not a copy fix: the dashboard was telling a parent something about
their TV that it had no evidence for, and recommending an action that would do nothing. The
distinction between *unknown* and *behind* is the whole change; no second freshness system was
introduced, and the W8.1 Now Playing freshness (which is about the playhead, not the version) was not
touched.

**TEST** Four live scenarios, in order, against the real TV:

| | Scenario | Expected | Result |
|---|---|---|---|
| A | TV current, artwork working | no stale banner | **PASS** — absent (refresh reported the TV on the same version) |
| B | only `GET /catalog/artwork` blocked | no stale claim, no update suggestion, TV still "on" | **PASS** — `{stale: false, suggestsUpdate: false, pill: "TV on"}` |
| C | document genuinely ahead of the TV (written from outside the dashboard, because publishing in the page sends the library immediately) | the stale message still appears | **PASS** — banner present with the new copy in the same block |
| D | `/status` blocked | "Unable to reach the TV." | **PASS** — unchanged |

Screenshots `f4-artwork-failure.png`, `f4-tv-stale.png`.

*Note on C:* the first attempt created a category through the dashboard and found no banner — which is
correct behaviour, because saving in the page updates the TV. The test now writes through the API, the
way "another phone" would, and the banner appears exactly as it should.

## F5 — Reachable Video Counts

**BEFORE** The library summary read `1 category · 1 collection · 1 video` while the collection inside
it read `50 videos`: two numbers for the same library, from the same screen flow, both of which the
parent would reasonably believe.

**CHANGE** One definition, used by every heading:

```js
function countsFor(nodeId) { ... videos: reachableVideoCount(nodeId) ... }

/** The videos a child can reach: one document video counts once, a collection counts what the TV
    holds for it, and a hidden video is not counted as reachable (it is reported as a hidden item). */
function reachableVideoCount(nodeId) {
    var total = 0;
    childrenOf(nodeId).forEach(function (child) {
        if (child.nodeType === VIDEO) { if (child.enabled !== false) total += 1; }
        else if (child.nodeType === SUBCATEGORY) total += containerVideoCount(child);
    });
    return total;
}
```

`nodeMeta` (category and collection headings), `libraryTotals` (the library summary) and
`removableVideoCount` (F2) all read from it, so the library, the category, the collection and the
removal question cannot disagree again.

**WHY** The audit's recommendation was to count the videos the child can reach, on both screens. The
hidden/unplayable distinction is preserved: a hidden video is counted in "N hidden items" and never in
"N videos", so the two numbers describe different things rather than double-counting the same thing.

**TEST** Live: read the library summary, the category tile, the category heading and the collection row
in one walk.

**RESULT** **PASS** — library summary `…50 videos`, category tile and heading `1 collection · 50 videos
· 1 hidden item`, collection row `50 videos`: one number, three places. Screenshots `f5-library-count.png`,
`f5-collection-count.png`.

## F6 — Conflict Version Number

**BEFORE** `Another phone or browser saved a change first (the TV is now on version 59), so this change
was not saved.`

**CHANGE** `openConflictDialog` no longer receives or prints the outcome's `serverVersion`:

```text
Another phone or browser saved a change first, so this change was not saved.
Nothing on the TV changed.
[Load the latest and start again] [Keep my change and save again] [Leave it for now]
```

The parameter was removed rather than left unused, so nothing can reintroduce it by accident.

**WHY** A catalog revision is an internal counter with no parent meaning. The explanation, the
reassurance that the TV is untouched and both safe actions are unchanged.

**TEST** Live: write to the catalog behind the page's back, rename something in the page, save, read
the dialog.

**RESULT** **PASS** — no "version" anywhere in the dialog; the explanation and both actions intact.
Screenshot `f6-conflict.png`.

## F7 — Duplicate Authorization State

**BEFORE** `Added by you · hidden · can't play yet` **plus** the badges `Hidden` and `Can't play yet` —
the same two facts twice in one row.

**CHANGE** For a video, the meta line is now only where it came from (`sourceFrom`), and the badges
carry the state. The badges, the `enabled` flag and the authorization logic are untouched.

**WHY** The badges are the louder, scannable statement; the meta line repeating them made a row read
like a log file. The audit explicitly said to keep the badges and not to redesign the
allow-the-source behaviour — neither was changed.

**TEST** Live: read the row text of the hidden, unplayable video.

**RESULT** **PASS** — `▶ / Disabled Demo / Added by you / Hidden / Can't play yet / ▲ ▼ ⋯`.
Screenshot `f7-authorization-row.png`.

## F8 — Collection Move Wording

**BEFORE** `Move to another category… — Keeps the video and everything inside it`, shown for
collections as well as videos.

**CHANGE** `Keeps everything inside it` — true for a video, a collection, and anything nested.

**WHY** The noun was wrong half the time. The audit asked for wording that works for both, without a
dynamic copy system.

**TEST** Live: open the menu on the 50-video collection.

**RESULT** **PASS** — `Keeps everything inside it`, and `Keeps the video…` appears nowhere in the
dashboard. Screenshot `f8-move-menu.png`.

## F15 — Unknown Video Error

**BEFORE** An unknown video id produced `That link could not be read.` — the generic fallback. The
extractor's own words for it were measured on the device first:

```text
502  {"error": "JSON response is too short"}                 (unknown video)
502  {"error": "Got error ERROR: \"This video is unavailable\""}   (unavailable video)
```

**CHANGE** Two things, because the second message exposed a second defect:

1. `FRIENDLY_FAILURES` gained `/json response is too short|no such|not found|404/` →
   **"Nothing was found at that link."**, and the unavailable family now says
   **"That video or playlist is not available on YouTube."** instead of claiming every unavailable
   thing is a playlist. Order matters and is asserted: the network patterns (offline, timeout,
   connect) are checked first, so a connection problem is never reported as a missing video.
2. `isParentReadable` now rejects the extractor's own shorter shapes
   (`got error`, `error:`/`error"`, `too short`, `stream info`). This is the defect the live test
   found: *"Got error ERROR: \"This video is unavailable\""* is 44 characters of readable English, so
   it was passing the "the project wrote this for a parent" filter and the translation never ran.

No 502 is mapped globally: only the messages whose meaning is "there is nothing there".

**WHY** The brief's requirement is exact — the unknown item gets its own sentence, and unrelated
failures keep appropriate handling.

**TEST** Live, five links through the real resolver and the real add screen: a valid video, a valid
playlist, a nonexistent video, an unavailable video, and a channel link (which must still produce the
F3 message).

**RESULT** **PASS**

| Link | Parent sees |
|---|---|
| valid video / valid playlist | the preview card, as before |
| nonexistent video | **"Nothing was found at that link."** |
| unavailable video | **"That video or playlist is not available on YouTube."** |
| channel | **"A whole channel can't be added here. Allow that channel in Settings, then add the videos or playlists you want from it."** (F3) |

Screenshot `f15-not-found.png`. A guard test asserts the network-before-missing ordering, the new
sentences, and that the readability filter rejects the extractor's text.

## F9 — Deferred

```text
F9=DEFERRED
REASON=Requires separate desktop UX decision; intentionally not part of W8.2 targeted corrections.
```

The desktop content column is still 728px at every width. No `max-width`, grid, Now Playing or
navigation change was made for it.

## Tests

```text
JVM                  846 / 846 debug, 846 / 846 release   (0 failures)
Dashboard JS          73 / 73 editor, 29 / 29 import, 41 / 41 ui   = 143 / 143  (was 135)
PLAYER tier           see Real Mi Box Verification
FULL tier             see Real Mi Box Verification
```

The dashboard suite gained **8 tests** (135 → 143), one per implemented finding, each asserting the
behaviour that was fixed rather than the shape of the code:

- F1: `.crumb` has a 40px minimum, is a flex box, and keeps its text size;
- F2: one `removableVideoCount`, used by both the menu and the confirmation, with the container/category
  rule asserted and the old row count asserted *absent*; the YouTube sentence and the `Remove` label
  still required;
- F4: `artworkKnown` set both ways, the four-state classifier with `unknown` decided before any
  comparison, both consumers gated on it, and the old bare comparison asserted absent;
- F5: `countsFor` reads `reachableVideoCount`, the summary and headings read the same fields, and hidden
  videos are excluded from the reachable count;
- F6: no `version` and no `serverVersion` in the dialog, both safe actions present, and the new call
  signature;
- F7: the meta line is only the source; the badges still exist;
- F8: the new wording present, the old wording absent;
- F15: the extractor patterns, the two new sentences, the ordering of the network patterns, and the
  readability filter's new rejections.

One existing JVM test changed: `CatalogImportRoutesTest` asserted the old "not a shelf" sentence; it now
asserts the new one **and** that "shelf" is absent, so the terminology cannot come back. No test was
deleted, weakened or skipped.

## Real Mi Box Verification

`adb devices` → `172.16.1.2:5555  device`; `ro.product.model = MIBOX4`;
`ro.build.version.release = 12`; app `tv.safetubeforkids.app` 0.10.0 debug, reinstalled from the W8.2
build and relaunched, PIN read through `DEBUG_GET_PIN`.

A purpose-built harness drove the real dashboard in a real browser against the real TV server and ran
**31 checks, 31 passed**: the breadcrumb at three widths, the four counts, the removal menu and
confirmation, the authorization row, the move wording, five link kinds, the conflict, and all four
freshness scenarios (normal, artwork-blocked, genuinely stale, unreachable) — then verified that the
library was left byte-identical (`cat-cartoon, i-cocomelon, i-demo-disabled`).

```text
PLAYER tier   41 / 41
FULL tier     56 / 56
```

## Responsive Verification

```text
360×800   light+dark   breadcrumb 40×78   overflow 0   clipped false
390×844   light+dark   breadcrumb 40×78   overflow 0   clipped false
412×915   light+dark   breadcrumb 40×78   overflow 0   clipped false
1280×900  light+dark   breadcrumb 40×78   overflow 0   clipped false
1440×900  light+dark   breadcrumb 40×78   overflow 0   clipped false
```

10 measurements, 0 problems. The crumbs row is exactly 40px, so the fix adds 12px of height to the
header block and no width; the h1 below it is unmoved.

## Screenshots

`docs/screenshots/w8_2_impl/` (15 files, 2.6 MB) — one or more per finding, plus the responsive
variants:

```text
f1-breadcrumb.png              f1-breadcrumb-360-light.png
f1-breadcrumb-1280-light.png   f1-breadcrumb-1440-dark.png
f2-remove-menu.png             f2-remove-confirm.png
f3-channel-error.png           f4-artwork-failure.png
f4-tv-stale.png                f5-library-count.png
f5-collection-count.png        f6-conflict.png
f7-authorization-row.png       f8-move-menu.png
f15-not-found.png
```

The audit's own screenshots in `docs/screenshots/w8_2/` are untouched and remain the "before"
evidence.

## Files Changed

```text
tv-app/app/src/main/assets/app.js                            F2, F4, F5, F6, F7, F8, F15
tv-app/app/src/main/assets/style.css                         F1
tv-app/app/src/main/java/tv/safetubeforkids/app/server/CatalogImportRoutes.kt   F3
tv-app/scripts/dashboard-catalog-ui.test.js                  8 new guards
tv-app/app/src/test/java/tv/parentapproved/app/server/CatalogImportRoutesTest.kt   F3 wording
docs/W8_2_IMPLEMENTATION_REPORT.md                           this report
docs/screenshots/w8_2_impl/                                  15 screenshots
```

`.kt` files changed: one (a string constant). No other Kotlin, no Gradle, no manifest, no database, no
schema, no route signature, no response shape.

## Security / Architecture Preserved

```text
PlaybackAuthorization          unchanged
allowed YouTube sources        unchanged
catalog membership semantics   unchanged
child playback restrictions    unchanged
unapproved URL handling        unchanged
Room / catalog schema          unchanged
catalog versioning             unchanged
sync architecture              unchanged
Apps/Kiosk runtime             unchanged
navigation architecture        unchanged (no route, breadcrumb or nav change)
Now Playing                    unchanged (no file in its path was edited for it)
dependencies                   none added; still no framework, no build step
```

Two sentences the brief protects are asserted by tests and confirmed live: *"Only what is listed here
can play on the TV. Adding something to your library does not allow it."* (untouched, now also more
visible because the counts agree) and *"It does not delete anything from YouTube."* (still the last
line of the removal confirmation, adding the video count in front of it rather than replacing it).

## Final Git State

```text
git rev-parse HEAD   2b14b89 + the W8.2 commits (see Final Status)
git status --short   clean after committing; docs/W8_2_UX_AUDIT.md and docs/screenshots/w8_2/ were
                     already present as untracked audit artifacts and are preserved
worktree             clean
```

## Final Status

```text
F1=PASS  F2=PASS  F3=PASS  F4=PASS  F5=PASS  F6=PASS  F7=PASS  F8=PASS  F15=PASS
F9=DEFERRED
F10..F14, F16..F20=NOT_IMPLEMENTED (deliberately)
JVM=846/846 debug + 846/846 release
DASHBOARD_JS=143/143
PLAYER=41/41   FULL=56/56
LIVE=31/31 on the real Mi Box   RESPONSIVE=10/10 measurements clean
SECURITY_MODEL_PRESERVED=YES
```

One thing found during the verification is **not** fixed and is reported rather than silently
changed: when a *playlist* link does not exist, the TV answers 200 with an empty video list and the
playlist id as its title, so the preview card shows a raw id such as
`PLzzzzzzzzzzzzzzzzzzzzzzzzzzzz` where a name belongs. It is outside F1–F8/F15 (no finding covers it),
it changes nothing about what the parent can do, and it is a one-line follow-up: treat a resolved
source whose title equals its own id as "nothing was found", exactly like F15's video case.
