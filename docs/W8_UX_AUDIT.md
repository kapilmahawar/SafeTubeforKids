# W8 — UX audit of the parent dashboard

**Baseline:** `953628e` (W7 complete, pushed). Working tree clean.
**Measured on:** Xiaomi Mi Box 4 / Android 12 (API 31) at `172.16.1.2:5555`, the app's own Ktor
server, headless Chrome over `adb forward`, at 360 / 390 / 412 / 1280 / 1440 px, light and dark.
**Evidence:** `%TEMP%\w8\before-*.png` (26 screenshots), `before-metrics.json` (geometry, computed
styles, headings, tap targets, images), `before-contrast.json`, `before-checks.json` (33 checks).

This audit was written from the running product, not from the code's intentions. Every number below
was measured; every finding names the file and the behaviour it comes from. Where the interface is
already right it says so, because W8's job is to improve what needs improving, not to redesign.

---

## 1. What is already good, and stays

Measured across all five widths, in both themes, on every screen the walk visited:

| Property | Measurement |
|---|---|
| Horizontal overflow | **0 px at 360, 390, 412, 1280, 1440** |
| Tap targets under 40 px | **0** (11 controls on mobile, 9 on desktop) |
| Buttons/links without an accessible name | **0** |
| Broken images | **0** |
| Sampled text contrast (WCAG AA, 4.5:1) | **passes** — e.g. the subtitle `rgb(90,100,116)` on white is 5.99:1 |
| Inline event handlers | 0 (`on*=` absent from `index.html`) |
| Browser storage keys | 3 (session token, theme, one dismissal flag) — never the library |
| Layout | no fixed-height traps, dialogs fit, bottom bar respects `safe-area-inset-bottom` |

The structural decisions W7 made are working and are not revisited here: one write path, immediate
publish, the conflict dialog, hash routes, the action table, the token palette, `theme.js` before
first paint, and the authorization boundary.

---

## 2. Findings

Each finding is stated as CURRENT → PROBLEM → PROPOSED → WHY → RISK. Ordered by how much a parent
feels it, not by how easy it is to fix.

### F1 — A cold load shows a blank page (highest impact)

- **CURRENT** `boot()` awaits `GET /status`, then `POST /auth/refresh`, then `GET /catalog`,
  `GET /catalog/artwork` and `GET /playlists` *before* calling `render()` for the first time. The
  measured loading-state capture is `innerText: "Skip to the content"`, 0 interactive elements,
  0 headings. On a slow phone-to-TV wifi that blank page lasts as long as the slowest call.
- **PROBLEM** The first thing a parent sees after opening the page is nothing at all. There is no
  loading state on a cold load, and no way to tell "working" from "broken".
- **PROPOSED** Render the shell and a labelled loading state synchronously, before the first `await`:
  the header, the section bar, and "Loading your library…" with a spinner in the content area. Keep
  the existing per-screen spinners for later loads.
- **WHY** It is the difference between "it is working" and "it did not load"; it also removes the
  only blank screen in the product.
- **RISK** Low: one render call moved earlier, plus the state that already exists.

### F2 — The library reads like a list of links, not a library

- **CURRENT** `#/library` renders `h1 Library`, the subtitle "This is what your child sees on the
  TV.", then two equal-weight buttons ("Add from YouTube", "New shelf"), then a home-screen banner,
  then a "1 video cannot play yet" banner, then a single bare row (`356 × 66`, radius `0`, no
  surface, no artwork), and finally a footer button "Send to TV now".
- **PROBLEM** Nothing says *how much* is in the library, the primary action is buried in a button
  pair, a promotional banner outranks the content, and the categories — the whole point of the
  screen — are the visually weakest thing on it. The footer "Send to TV now" duplicates Settings and
  sits where a parent expects nothing.
- **PROPOSED** A summary line with real counts ("2 categories · 52 videos"); categories as cards
  (surface, radius, shadow, 64 px+, chevron) that carry their own counts; the home-screen hint moved
  *below* the list; the duplicate footer action removed (Settings → TV keeps it); a single primary
  "Add to library" action, with "New category" as a quiet secondary.
- **WHY** The first screen should answer "what is in my child's library" and "how do I add
  something" without reading anything twice.
- **RISK** Medium: cards replace bare rows, so the layout guards and the browser walk must be updated
  with it.

### F3 — The unplayable-video warning counts items the parent has hidden

- **CURRENT** The library showed "1 video cannot play yet" for a library whose only unplayable video
  (`i-demo-disabled`) is also `enabled: false`, i.e. invisible to the child.
- **PROBLEM** A warning about something the child cannot see is noise, and noise is how warnings get
  ignored — the one place this product cannot afford it.
- **PROPOSED** Count and warn only about videos the child can actually reach (visible themselves, and
  not inside a hidden container). A hidden unplayable video keeps its own "Hidden" badge and is dealt
  with in its own row.
- **WHY** The warning's whole job is to explain a real, current inability to play.
- **RISK** Low: one predicate.

### F4 — A collection screen offers an action that cannot work

- **CURRENT** `addActions(parentId)` returns "New folder" for any parent, so a collection
  (`SUBCATEGORY`) screen shows a "New folder" button. `CatalogEditor.addSubcategory` refuses it:
  "a SUBCATEGORY cannot hold a subcategory".
- **PROBLEM** A visible button that can only fail, on the screen a parent reaches most often.
- **PROPOSED** Offer "New collection" only where a collection may live (a category); a collection
  screen offers "Add to library" and its own menu.
- **WHY** Every control must be able to succeed; a refusal dialog for a control the product itself
  offered is a bug, not a message.
- **RISK** Low.

### F5 — The vocabulary is still partly the project's

- **CURRENT** "Shelf", "Folder", "Add from YouTube", "Allowed sources", "Send to TV now",
  "Reload from the TV", "Check my library", "Delete".
- **PROBLEM** "Shelf" is a TV-UI metaphor a parent has to learn; "folder" promises a filesystem that
  does not exist; the rest mixes product verbs with their implementation ("Reload from the TV").
- **PROPOSED** The decision table in §3 below.
- **WHY** The interface should be understandable without documentation — that is W8's central
  requirement.
- **RISK** Medium: 20-odd strings in `app.js`, the copy assertions in three test suites, and the
  walk's selectors all move with it.

### F6 — Reordering is discoverable only by opening a menu

- **CURRENT** "Move up" / "Move down" live inside the per-row ⋯ menu (two taps, and only if the
  parent thinks to open it). There was no `Move to top` / `Move to bottom`, and nothing on screen
  suggests order is editable at all. The measured item menu: Rename, Hide, Move up, Move down,
  Move to another shelf…, Choose the picture, Delete.
- **PROBLEM** The parent cannot see that order is theirs to set; and "move down five times" is the
  only way to put something last.
- **PROPOSED** Inline ▲▼ on each row of a category or collection with two or more items (one tap),
  plus "Move to top" and "Move to bottom" in the menu, plus the existing "Move to another …". No
  drag-and-drop, and the accessible path stays.
- **WHY** Order is the feature the child sees; it has to be one tap and visible.
- **RISK** Medium: row width at 360 px must be measured (the row already carries artwork, a title, a
  meta line, badges and a menu button).

### F7 — Deleting does not say what it does *not* do

- **CURRENT** The confirm dialog says "This deletes it and the N things inside it, from your library
  and from the TV. You can add them again later." with a button labelled "Delete".
- **PROBLEM** "Deletes … from the TV" is the wrong mental model twice over: it is not deleted from
  YouTube, and "the TV" is not where the library lives. A parent hesitating over a destructive button
  deserves the exact scope.
- **PROPOSED** "Remove from library?" / "This removes it and the N items inside it from your child's
  SafeTube library. **It does not delete anything from YouTube.**" / buttons "Cancel" and "Remove".
  Hiding keeps its own wording ("Your child won't see it — you can restore it later").
- **WHY** Destructive actions must be unambiguous, and the product must never imply it deleted
  somebody's YouTube content.
- **RISK** Low.

### F8 — Settings is one long list of seven panels

- **CURRENT** `#/settings` renders Your TV → Screen time → Allowed sources → Your library → What has
  been watched → Look → Something wrong?, in that order, with no grouping and no headings between
  them.
- **PROBLEM** Nothing is wrong individually; together they read as an administration console, and
  "Your library" (update/check/reload) sits in the middle of it.
- **PROPOSED** Five named groups: **TV** (status, controls, update), **Time limits** (limits,
  bedtime, lock, bonus), **Content** (allowed YouTube sources, import/export), **Appearance**
  (light/dark), **Troubleshooting** (reload the library, check for problems, error report,
  disconnect). Every control keeps working; only the presentation changes.
- **WHY** Grouping is what turns a console into settings.
- **RISK** Low, but the JS guard test that pins panel order must move with it.

### F9 — An error from YouTube can reach the parent as sentence-shaped jargon

- **CURRENT** `POST /catalog/import/resolve` failures are surfaced with `data.error`, whatever it
  contains: the route passes `e.message` through from the NewPipe extractor ("Could not get
  PlaylistExtractor for url: …"), and the dashboard prints it verbatim.
- **PROBLEM** A normal parent cannot act on an extractor message, and it reads like a crash.
- **PROPOSED** Map known failures to parent sentences (private/deleted playlist, nothing usable in
  it, the TV could not reach YouTube, that is not a YouTube link) and fall back to a neutral
  sentence; never print a raw exception. Add explicit states for "this playlist has nothing that can
  be added", "these videos are already in that category", and "that link is a channel".
- **WHY** W8 asks for human-readable errors, and the security model is unaffected either way.
- **RISK** Low: the mapping is client-side; the server keeps its status codes and shapes.

### F10 — Artwork has no alt text and no failure path

- **CURRENT** Every `<img>` in the library is built with `alt=""` (decorative) and no `onerror`
  handler; the placeholder is used only when there is no URL at all.
- **PROBLEM** A screen reader gets no signal from a picture that *is* the item's identity, and a URL
  that stops resolving renders the browser's broken-image glyph.
- **PROPOSED** `alt` = the item's title, `decoding="async"`, and an `error` handler that swaps in the
  existing placeholder node so a failure degrades to the same visual language. Ratios stay 16:9 and
  the box is reserved, so nothing jumps.
- **WHY** Cheap, and it is the difference between a polished card and a broken one.
- **RISK** Low.

### F11 — The add screen's primary verb is the implementation's

- **CURRENT** The add screen says "Paste a YouTube link", with the button **"Check this link"**, then
  a preview, then "Add to" (a `<select>`), then the approval switch, then "Add N videos".
- **PROBLEM** "Check this link" is a step of the machine, not a decision of the parent; the preview
  has no heading, so the screen never says what it is doing; the button label changes between playlists
  and videos ("Add 50 videos" / "Add this video"), which is good, but the *flow* has no shape.
- **PROPOSED** Keep the order exactly (paste → resolve → confirm), and give it a shape: heading
  "What did you find?" over the preview, the destination under "Where should it go?", the switch
  under "Let my child watch it", and one primary button "Add to library" (count in the sentence above
  it). Rename the first button to **"Continue"**.
- **WHY** One decision per step, in the parent's words, with no technical field ever shown (there is
  none today — this keeps it that way).
- **RISK** Low.

### F12 — Nothing tells the parent when a video *is* ready

- **CURRENT** An unplayable video is badged "Cannot play yet" and the screen offers "Allow N sources".
  A playable video carries no badge at all.
- **PROBLEM** The product explains the exception but never confirms the normal case, so the parent
  cannot tell whether allowing the source worked.
- **PROPOSED** The item menu states the current state in words ("Ready to watch" / "Can't play yet —
  its YouTube source isn't allowed"), and after allowing a source the toast confirms it. Keep badges
  for the exception only, so a normal library stays quiet.
- **WHY** Confirmation, not decoration: the security distinction stays visible without turning every
  row into a status table.
- **RISK** Low.

### F13 — Small consistency and polish defects found while measuring

- **CURRENT** `font-weight: 750` on titles (a variable-font value most system fonts snap); the row
  surface is transparent while panels have a surface; `.btn--sm` and `.btn` differ only in height;
  badges mix two radii (`999px` and `12px`); the dark theme's `.toast--ok` uses `#04120b` on green
  (measured 8.6:1 — fine, but the tone is arbitrary); there is no skeleton for a loading card.
- **PROBLEM** Nothing here breaks; together they read as "assembled" rather than "designed".
- **PROPOSED** One spacing scale, one radius scale, one weight per role (700 for titles, 600 for
  emphasis, 400 for body), a single badge shape, and a `--ring`/`--skeleton` token so loading and
  focus look deliberate in both themes.
- **WHY** Consistency is most of what "polished" means at this size.
- **RISK** Low.

### F14 — Desktop is a stretched phone

- **CURRENT** At 1280 and 1440 px the content column is 760 px wide with the same single-column rows,
  and the section bar sits under the header. Measured: no overflow, no small controls, but 480 px of
  empty margin on each side of a 6-row list.
- **PROBLEM** It works and it is not an admin panel, which was W7's goal — but it also wastes the one
  thing a desktop has.
- **PROPOSED** Keep one column for lists, and let *category cards* flow into a grid of two or three
  at ≥1000 px, with the same card design and the same order. No sidebars, no tables.
- **WHY** More room should mean more content visible, not more chrome.
- **RISK** Low.

### F15 — Renaming an existing video is refused by the model (PART 11 investigation)

- **CURRENT** `CatalogEditor.rename` refuses `VIDEO` nodes: "a video keeps the name it was added
  with; rename the shelf or subcategory instead". The add screen *does* let a parent name a video at
  creation time, and the server accepts any non-blank title on any node type.
- **PROBLEM** The asymmetry is the problem: the same parent, two minutes later, cannot fix a title
  YouTube gave them (or one they typed wrong) without removing and re-adding the video.
- **PROPOSED** Allow renaming an existing video. The node's identity stays `id`, its YouTube identity
  stays `youtubeVideoId`, its provenance stays `youtubePlaylistId`, its position, `enabled` state,
  resume history and the playback authorization path are untouched — a rename changes one string.
- **WHY** It is a display name, the model already supports it at creation, and the catalog contract
  has no rule against it.
- **RISK** Low, and verified: the refusal is the editor's own rule, not the server's. Two editor tests
  pin the old behaviour and are updated with the change, plus the destructive-rename guard
  (`problems()` and the server validator) is unchanged.

---

## 3. Terminology decision

| Concept (code) | W7 said | W8 says | Why |
|---|---|---|---|
| `CATEGORY` — a titled row on the TV | Shelf | **Category** | "Shelf" needs TV-UI knowledge; "category" is what a parent already calls a group of things |
| `SUBCATEGORY` — a card that opens a list of videos | Folder | **Collection** | It holds videos, not files; a folder promises a tree the product does not have |
| The whole document | Library | **Library** | Already right |
| An imported playlist / channel / video that may play | Allowed source | **Allowed YouTube source** | Names where it comes from and what it grants |
| Being in the library | (unstated) | **In your library** | The distinction the whole security model rests on |
| Permission to play | Approved / authorized | **Allowed for your child** | A parent's word for a parent's decision; never "authorized" |
| The TV's copy being stale | "Send to TV now" | **Update the TV now** | Names the outcome, not the transport |
| Re-reading the document from the TV | "Reload from the TV" | **Reload the library** | The parent reloads a library, not a TV |
| `CatalogEditor.problems` | "Check my library" | **Check for problems** | Says what it does |
| Removing a node | Delete | **Remove from library** | Accurate about scope, and never implies YouTube was touched |
| A node hidden from the child | Hide / Hidden | **Hide from my child** / **Hidden** | Already right |
| A video whose source is not allowed | "Cannot play yet" | **Can't play yet** | Already right; kept |

Code identifiers, class names, route paths, database columns and log lines keep their technical
names. Only the strings a parent reads change.

---

## 4. What W8 will not touch

- The catalog document, `PUT /catalog`, the version precondition and the 409 path.
- Room v9, `catalog_nodes`, `catalog_metadata`, the sync client and its ordering rule.
- `PlaybackAuthorization`, `channels`/`videos`, `POST /playlists`, the meaning of "allowed".
- `AppsRoutes.kt` and the kiosk runtime (PART 23): the routes stay, and no Apps/Kiosk/Installed-Apps
  surface returns to the dashboard — a guard test already fails the build if one does.
- The `theme.js` architecture (token palette, `data-theme`, `localStorage`, before-first-paint).
- The action table, the hash routes, the "no framework, no build step" decision.

## 5. Definition of done for W8

1. Every finding above is either fixed or explicitly declined with a reason, in
   `docs/W8_IMPLEMENTATION_REPORT.md`.
2. The measured walk passes at 360 / 390 / 412 / 1280 / 1440 px in both themes, with before/after
   screenshots for the eight states the brief names.
3. The 25-step browser scenario runs against the real TV and leaves the library exactly as it found it.
4. JVM tests (debug and release), the dashboard suites, and the PLAYER and FULL device tiers all pass.
5. No change to the database, the catalog contract, the authorization boundary, or the kiosk runtime.
