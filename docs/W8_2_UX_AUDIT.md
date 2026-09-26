# SafeTube W8.2 UX Audit

## Audit Metadata

```text
HEAD=2b14b89e51664c7e1203dbe6bef345479f85c319
BRANCH=main (fork/main; fork and origin are the only remotes, HEAD == fork/main)
WORKTREE=clean before and after the audit (git status --short empty; see §Git Safety below)
DATE=2026-09-26
DEVICE=Xiaomi Mi Box 4 (MIBOX4), adb 172.16.1.2:5555
ANDROID_VERSION=12 (API 31)
APP=tv.safetubeforkids.app 0.10.0, debug build; the five dashboard assets inside the installed APK
    are byte-identical to HEAD (verified by SHA-256 against the repo: app.js, index.html, style.css,
    theme.js, catalog-editor.js)
DASHBOARD=http://127.0.0.1:8080 (adb forward → the TV's own Ktor server)
BROWSER=headless Chrome over CDP, emulated at 360×800, 390×844, 412×915, 1280×900, 1440×900
METHOD=40 live captures in the first pass + 7 follow-up captures, a page-side measurement probe on
       every capture (geometry, computed styles, contrast, tap targets, headings, artwork, timing),
       console and network logging, and one isolated A/B test of a suspected defect.
EVIDENCE=%TEMP%\w8_2\ (measures.json, console.json, network.json, timing.json, follow-up.json) and
         docs/screenshots/w8_2/ (34 screenshots, 5.0 MB)
```

Nothing in the project was modified: no source, no test, no configuration, no database, no API. The
only device state touched was temporary and restored — see §Audit Actions and §Git Safety. Harness
scripts live in `%TEMP%\w8_2\` and are not project files.

## Executive Summary

The dashboard is no longer an administration panel. It is a **library app with a TV status card on
top of it**, and the audit found no P0 defects: every screen loads, no console error or CSP violation
occurred in a full walk (the three console entries are the audit's own deliberate 400/409/502
probes), no horizontal overflow appeared in any of the 47 captures, no image failed to load, and
**none of the 23 sampled text/background pairs fell below the 4.5:1 contrast threshold** on any
screen in either theme.

The problems that remain are real but narrow, and fifteen of them are recorded below. Four are worth
acting on:

1. **A destructive action understates what it destroys.** A collection whose heading says
   "50 videos" offers "Remove from library" with the explanation "Removes this and the **0 items**
   inside it", and the confirmation says only "This removes it from your child's SafeTube library".
   Removing that container takes the 50 videos off the TV. A parent reading either sentence would
   reasonably expect one card to disappear.
2. **A failed artwork read is reported as a stale TV.** With only `GET /catalog/artwork` blocked —
   TV up, pill reading "TV on" — the library displayed "Your TV does not have the latest yet". The
   isolated A/B test is in §Loading / Empty / Error States: after a refresh the banner is absent, and
   blocking only that one read brings it back.
3. **One stale term survived the W8 rename.** Refusing a channel link says *"A whole channel is not a
   shelf."* The UI renamed "shelf" to "category" in W8; the sentence now uses a word the interface
   never shows, in the one place a parent is already confused (a link the product would not take).
4. **The breadcrumb is a 28px tap target.** "Your library" on every child screen measures 74×28 at
   390px — the only control in the whole audit below the 40px minimum the brief sets, and it is the
   control a parent uses to get back.

The rest are polish: two screens disagree about how many videos the library holds, the conflict
dialog mentions a catalog version number, a row states the same two facts twice, desktop never uses
more than a 728px column, and a few labels are duplicated or vague. None of them block use.

The W8.1 Now Playing card is **appropriate and well judged**: distinct for idle / playing / paused /
unreachable / stopped, 8.5% of the viewport when idle and 28% when playing, with artwork, a moving
playhead and two controls that reuse the endpoints the dashboard already had.

## What Already Works Well

Measured positives, with the evidence that produced them (47 captures, both themes, five widths):

- **No console errors, no uncaught exceptions, no CSP violations.** The three `console.json` entries
  are the audit's own probes (502 unresolvable link, 400 channel link, 409 conflict).
- **No horizontal scrolling anywhere** — `scrollWidth - innerWidth == 0` on all 47 captures, including
  a 78-character category name.
- **One tap-target violation in the whole audit** (the breadcrumb, F1). Everything else, including the
  Now Playing controls, is ≥40px.
- **No unnamed controls**: zero buttons or links without a text label or `aria-label`.
- **No broken artwork**, every picture 16:9 within 2% (`168×94` natural, `104×59` box, ratio 1.76),
  lazy-loaded, and carrying the item's name as `alt`.
- **Contrast passes everywhere** — 23 sampled pairs per screen (title, subtitle, meta, badge, buttons,
  tabs, pill, field labels and hints, card titles and notes, dialog text, menu items, inputs), zero
  below 4.5:1 in light or dark.
- **Heading order is correct**: `h1` screen title → `h2` group/section → `h3` card title.
- **Cold load to usable is under 1.3 seconds** at every width (measured 929–1233 ms, phone-sized
  viewport, against the TV's server over adb).
- **The catalog-membership distinction is explained in parent language**, which is the hardest copy in
  the product and the best sentence in it: *"Only what is listed here can play on the TV. Adding
  something to your library does not allow it."*
- **Now Playing never confuses its states.** Idle, playing, paused, unreachable and stopped each have
  their own words and shape; "Unable to reach the TV." is never shown as "nothing is playing".
- Long names wrap instead of clipping (the 78-character category renders on two lines; the `h1` box
  grows to 94px, no clipping, no overflow).

## Library Home

`library-360-light`, `library-390-light`, `library-412-light`, `library-1280-light`,
`library-1440-light`, `library-1440-dark`, `library-with-audit-categories-mobile`

Observed order on a phone: Now Playing row (8.5% of the viewport when idle) → "Your library" + counts
summary → "Add to library" (primary) and "New category" → category cards with counts, a chevron, ▲▼
and ⋯ → the home-screen hint → the bottom navigation. On desktop the same content sits in a 728px
centred column.

- **Hierarchy is right**: the answer to "what is on?" comes first, the library second, and the
  primary action is the only filled button.
- **Category cards read well**: title, "1 collection · 1 video · 1 hidden item", chevron, reorder
  arrows, overflow menu. A parent can see what a category holds without opening it.
- **The library has no pictures at all** (categories deliberately carry none, W6.1). With nothing
  playing, the first screen a parent sees is therefore text-only apart from the brand dot and two
  emoji. It is coherent, but it is the least "consumer app" looking screen in the product — see F14
  and the Visual Polish section.
- **Counts disagree with the category screen** (F5): the summary says "1 video", the category screen
  says the collection holds "50 videos".
- Unused space on desktop is large (F9).

## Now Playing

`now-playing-idle`, `now-playing-playing`, `now-playing-paused`, `now-playing-resumed`,
`now-playing-stopped`, `now-playing-unreachable`, `now-playing-desktop`, `now-playing-playing-dark`

| State | Card | Copy | Height |
|---|---|---|---|
| Idle | one line | "Nothing is playing right now" | 72px (8.5%) |
| Playing | artwork, title, `▶Playing on TV`, `0:09 / 3:49`, bar, Pause, Stop | as shown | 239px (28%) |
| Paused | same, `❙❙Paused on TV`, position frozen | as shown | 239px (28%) |
| Unreachable | one line + Try again | "Unable to reach the TV." | 91px (10.8%) |
| Stopped | collapses to idle | "Nothing is playing right now" | 72px |
| Desktop (playing) | same card, 160px artwork | as shown | 217px in a 900px viewport (24%) |

Product review (the brief's ten questions):

1. **Too large?** On a phone, 28% while playing. Not excessive for a media card, but it is the single
   largest element on the first screen and it pushes the library below the fold. On desktop 24% of a
   900px viewport in a 728px column is comfortable.
2. **Too small?** No. Idle at 72px is discreet.
3. **Does it dominate the Library?** While something plays, yes — deliberately and, in my judgement,
   correctly: it is the only thing on the screen that is *changing*.
4. **Does it duplicate the header pill?** They carry the same sentence in two sizes ("▶ title" and the
   card). That is a deliberate "answer from any screen" + "detail on the library" pair, and the pill
   is the only status visible from Settings or the add flow. Kept, but it is duplication a designer
   could argue about (P3, F16).
5. **Is the artwork useful?** Yes — it is the fastest way to recognise what is on, and it is the only
   picture a parent sees unless they open a collection. It falls back to YouTube's thumbnail for the
   reported id when the TV has no cached picture, then to a placeholder.
6. **Is progress useful?** Yes, and it is honest: the position comes from the TV's own playhead and is
   interpolated between polls, so it moves smoothly and never runs past the duration.
7. **Are Pause and Stop appropriately prominent?** They are two 40px neutral buttons under the card —
   leading with Pause (the likely intent) and Stop beside it, both reusing the endpoints the dashboard
   already had. They do not shout, which is right for a card whose job is information.
8. **Is the TV-status vs library-content distinction clear?** Yes: different label ("TV" vs "Your
   library"), different shape (one line vs card), and the card never lists library items.
9. **Useful parent feature or debugging panel?** A useful feature. It answers the question the brief
   says a parent opens the page with, using words a parent would use. It does not leak a video id, a
   playlist id or a version number.
10. **Coherent in dark mode?** Yes — surface `rgb(23,28,35)`, title `rgb(238,241,246)`, artwork
    unchanged; no light-on-dark problem, no border that disappears.

## Category / Shelf

`category-mobile`, `category-desktop`, `category-desktop-dark`, `category-long-name-mobile`,
`authorization-state`

Screen: breadcrumb ("Your library" / category), `h1` title, one-line summary
("1 collection · 1 video · 1 hidden item"), two actions plus the ⋯ menu, then the rows.

A parent can immediately see what is inside (the summary), what can be watched (the 50-video
collection row, the hidden/unplayable badges), and what can be changed (Add to library, New
collection, ▲▼, ⋯). Rows carry artwork, a title, a meta line, badges and two reorder arrows plus an
overflow menu — that is a media list, not a table.

Problems: the summary disagrees with the collection row (F5); the meta line repeats the badges (F7);
the breadcrumb is a 28px target (F1).

## Collection / Playlist

`collection-mobile`, `collection-desktop`

A collection whose episodes live on the TV shows: breadcrumb "Your library / Cartoon / CoComelon",
`h1` "CoComelon", "50 videos", "Add to library" and ⋯, and an explanatory empty state:
*"These videos come from the TV — This collection plays 50 videos that the TV keeps up to date from
the playlist itself, so there is nothing to list here. Anything you add sits alongside them."*

That copy is exactly right and turns a confusing situation (a 50-video collection with an empty list)
into an explanation. A collection with document children renders the same rows as a category.

It reads as a parent managing a child's media library, **not** an administrator managing records —
with one exception: the Remove flow's "0 items inside it" (F2), which is the moment the interface
briefly sounds like a database view of the same collection it just described as 50 videos.

## Add Content

`add-content-mobile`, `add-content-preview`, `error-unresolvable-link`, `error-channel-link`,
`error-artwork-unavailable`

Walked live with a real playlist (CoComelon – Season 2, resolved from YouTube by the TV).

| Question the brief asks | Observed |
|---|---|
| 1. How to add something | "Add to library" is the only filled button on the library screen and on every category/collection screen |
| 2. Video or playlist? | The parent does not have to decide — one link field, and the TV reports which it found |
| 3. Where does the link go? | One labelled field, "YouTube link", with the hint "A playlist link, or a link to one video." |
| 4. What does the preview mean? | "What did you find?" over a card: artwork, "CoComelon - Season 2", "50 videos" |
| 5. Where will it appear? | "Where should it go?" with paths ("Cartoon", "Cartoon / CoComelon") |
| 6. Can the child watch it? | Stated on the same screen: "This is already one of your allowed sources, so it will play as soon as the TV gets it." |
| 7. What does the switch mean? | "Let my child watch it" + "Adds this to your allowed sources, so its videos can play." |
| 8. What happens after publishing? | A toast that says which happened: "…— saved. Your TV has it now." |

Places a parent could still ask "what does this mean?":

- **The primary button of the whole flow is also the page's title and the breadcrumb** — "Add to
  library" appears three times on that screen (F13).
- **A link that resolves but cannot be used** says only "That link could not be read." — the friendly
  "Nothing was found at that link." mapping exists but was not reached (F15).
- **"A whole channel is not a shelf."** (F3) — the one genuinely confusing sentence in the flow.
- Resolving a 50-video playlist takes several seconds on this hardware with no progress indicator
  beyond the inline spinner; acceptable, and the spinner is present.

## Authorization / Playback

`authorization-state`

The case exists in the live library: a hidden video whose source is not allowed
(`Disabled Demo`). Observed exactly as designed:

```text
▶ | Disabled Demo | Added by you · hidden · can't play yet | [Hidden] [Can't play yet] | ▲ ▼ ⋯
```

The catalogue-membership distinction is preserved in words a parent can act on, the badge is text
(not colour-only), and the category-level warning appears only when a *visible* video cannot play
(tested in W8; the hidden one here does not raise the banner). Nothing was changed during the audit.

Two polish issues: the meta line and the badges state the same two facts (F7), and the two badges
together ("Hidden" + "Can't play yet") describe a state whose remedy is not offered on that row — the
item menu does not list "Allow this source" for a hidden video, only the category banner does.

## Reordering

Observed on the library (`▲ ▼ ⋯` per category), on a category (`▲ ▼ ⋯` per row, `collection-mobile`),
and in the item menu ("Move up", "Move down", "Move to the top", "Move to the bottom", "Move to
another category…").

- **Discoverable**: the arrows are visible without opening anything, and they are the only icon-only
  controls with a direction glyph.
- **Usable**: 40px targets, disabled at the ends, one tap per step, with the four journeys in the menu.
- **Not added**: no drag-and-drop was introduced in W8 and this audit does not recommend it.
- The arrows appear only when a list has two or more items, so a single-item list does not show dead
  controls. Reasonable.
- Minor: the arrows are glyphs (`▲ ▼`) whose accessible names come from `aria-label`
  ("Move X up"/"Move X down") — correct, but they are visually tiny text inside 40px buttons and only
  differ from the ⋯ by glyph. A parent who does not recognise them has the menu as a fallback, and the
  menu spells the moves out.

## Delete / Hide

`item-menu-mobile`, `delete-confirm-mobile`

Wording, as observed:

```text
Hide from my child            It stays in your library, but the TV stops showing it
Remove from library           Removes this and the 0 items inside it        (F2)
Remove “CoComelon” from your library?
This removes it from your child's SafeTube library, on this phone and on the TV.
It does not delete anything from YouTube.
[Remove] [Cancel]
```

- **The distinction is right**: hiding is described as reversible and staying in the library; removal
  is described as leaving the library; and the confirmation explicitly says YouTube is untouched —
  which is the sentence that matters most for a destructive button.
- **Discoverability is right**: everything lives in the per-item ⋯ menu, which is also where the
  editing and reordering live; nothing destructive is a single tap.
- **Confirmation is right for items** (a modal with Cancel focused first as the last action) — and
  **wrong for the collection case**, where the consequence is far larger than the sentence says
  (F2: 50 videos on the TV go with it).
- **Risk of accidental action** is low: two taps minimum, the confirm button reads "Remove", and
  Cancel is present. No undo exists after confirming, which is acceptable given the wording — once
  the wording is accurate.

## Editing

Observed: the item menu offers **"Edit name"** for a collection and for a video; the dialog says, for
a video, "The video itself is unchanged; only the name your child sees." The W8 limitation this
project used to document — *a video keeps the name it was added with* — **no longer exists**: the
editor was changed in W8 (commit `1820c0c`) and the rename now applies to videos, leaving the video
id, provenance, position, enabled state and resume history untouched.

So the audit's answer to the brief's question is: the UI **clearly allows editing**, it does not
appear editable and then fail, and there is no longer a limitation to explain around. What is *not*
editable from the dashboard: a source's display name (it comes from YouTube's own title), a video's
duration or artwork (they come from the TV), and anything about the child's actual watch data.

## Settings

`settings-mobile`, `settings-desktop`, `settings-desktop-dark`

Structure: `h1` Settings → groups (TV, Time limits, Content, What has been watched, Appearance,
Troubleshooting) → cards. Every control in it corresponds to real functionality; none is a mock.

| Option | Classification | Note |
|---|---|---|
| "What the television is doing right now" + status | PARENT-FRIENDLY | |
| "Update the TV now" | PARENT-FRIENDLY | |
| "SafeTube 0.10.0 on the TV" | TECHNICAL BUT NECESSARY | belongs in Troubleshooting (F11) |
| Watched today / Left today / progress | PARENT-FRIENDLY | |
| "Lock the TV" / "+15 minutes" | PARENT-FRIENDLY | |
| "Change the daily limits", 7 numeric fields, "−1 for no limit" | CONFUSING (P2) | a negative number as the "no limit" convention (F17) |
| Bedtime start/end time fields | PARENT-FRIENDLY | |
| "Allowed YouTube sources" + explanation | PARENT-FRIENDLY | the clearest copy in the product |
| "Playlist · 50 videos ready", "Remove" | PARENT-FRIENDLY | "ready" is vague (F12) |
| "Save the list to a file" / "Load a saved list" | PARENT-FRIENDLY | export/import, named by outcome |
| "What has been watched" + recent list | PARENT-FRIENDLY | group and card share a label (F10) |
| Light / Dark | PARENT-FRIENDLY | |
| "Check for problems", "Reload the library" | PARENT-FRIENDLY | |
| "Copy the error report", raw crash text | TECHNICAL BUT NECESSARY | already in Troubleshooting, as it should be |
| "Disconnect this phone" | PARENT-FRIENDLY | |
| Resetting the TV's PIN | MISSING (P3) | it exists only on the TV; not a dashboard gap, but a parent who has forgotten the PIN has no pointer to it here (F18) |
| Anything about kiosk/apps | not present | correct: W7 removed it and no guard regressed |

## Navigation

Observed: bottom bar (Library / Settings) always present on phones, header tabs on desktop; the
brand returns to the library; breadcrumbs on category, collection and add screens ("Your library /
Cartoon / CoComelon"); the add screen's crumb returns to the library; item menus and confirmations are
modals; the conflict dialog offers two safe paths and no third one.

- **No dead ends found** in this walk. Every screen has a labelled way back, and every modal has a
  Cancel/Close.
- **Browser Back** was exercised implicitly by the hash router (each screen is a real URL and the walk
  navigated by hash repeatedly); no unexpected jump or lost state appeared.
- **Deep links work** (`#/library`, `#/shelf/<id>`, `#/folder/<id>`, `#/add/<id>`, `#/settings`).
- **Focus/scroll**: the Now Playing card is painted rather than re-rendered (W8.1) precisely so a poll
  does not disturb the parent; the audit saw no scroll jump while playhead updates ran.
- Minor: after a category is removed the parent is returned to the library (correct), but if a
  category is removed *while open* the screen shows "That category is gone" with two actions before
  the redirect — acceptable, slightly abrupt (P3).

## Mobile UX

360×800, 390×844, 412×915, 47 captures.

- **Horizontal overflow: none** (measured `0` everywhere), including the long-name and dialog cases.
- **Tap targets**: one violation, the breadcrumb, 74×28 (F1). All other controls ≥40px; the reorder
  arrows, the ⋯ menu, the Now Playing controls, the bottom tabs and the dialog buttons all measured
  40–46px.
- **No clipped text or buttons**: the 78-character category name wraps to two lines; long video titles
  wrap inside the row; nothing measured an overflow.
- **Dialogs**: the modal is bottom-anchored on a phone, fits the viewport (`.dialog__inner` max-height
  88vh with internal scroll) and its actions are full-width stacked buttons — comfortable to tap.
- **Keyboard interaction**: the add screen uses `type="url"` with `autocapitalize/autocorrect/spellcheck`
  off and normal flow (no fixed element pinned to the bottom of the form), so a virtual keyboard
  cannot hide the primary action. Not verified with a real on-screen keyboard (documented limitation).
- **Vertical scrolling**: the library needs one screen of scrolling on a phone with the Now Playing
  card playing; with a 3-category library the whole page is about two screens. No excessive length.

## Desktop UX

1280×900 and 1440×900, light and dark.

- **Content width is a fixed 728px column at both widths** (measured: the `h1` box is 728px wide at
  1280 and at 1440), so the wider screen shows identical content with ~356px more empty margin on each
  side. Not broken, but not "intentionally designed" either (F9).
- **Category cards do flow into a grid** at ≥760px (`auto-fill, minmax(240px, 1fr)`), so a library
  with several categories uses two or three columns inside that 728px column.
- **Navigation**: the bottom bar becomes a header row of tabs; the ordering stays clear.
- **Now Playing** sits at the top of the column, 217px tall, artwork 160px — proportionate.
- **No sidebar, no table, no dense admin grid**: the desktop presentation keeps the phone's mental
  model, which is what W7 intended. The cost is unused width, not wrong structure.
- **Dark desktop** (`settings-desktop-dark`, `library-1440-dark`, `category-desktop-dark`) is
  consistent with light: same surfaces, no mixed-theme components.

## Dark Mode

Verified on library (390/1440), category (desktop), settings (desktop), Now Playing (390), and the
state cards. Findings:

- **No contrast failure**: 23 sampled pairs per screen, none below 4.5:1 in dark (surfaces
  `rgb(23,28,35)`, titles `rgb(238,241,246)` = 15.1:1, secondary text `rgb(165,176,191)` = 7.8:1).
- **No white flash**: the theme is applied by `theme.js` before first paint (W8); the audit's dark
  captures never showed a light surface mid-load.
- **No mixed components**: cards, dialogs, menus, badges, inputs and the progress bar all follow the
  same tokens; the pill, the tabs and the Now Playing card all repaint.
- **Artwork** is unaffected by the theme (thumbnails keep their own surfaces), which is correct.
- **Nothing looks disabled that is not**: the quiet Now Playing row, the secondary buttons and the
  meta text are the three greys in both themes, and their contrast is measured above 4.5:1.
- One nit: the dark theme's green "playing" accents (`--ok` `#57d094` on `--ok-soft` `#14291f`) and the
  `❙❙` paused glyph in `--ok` are the only two places where a status is conveyed with a colour as
  well as words — the words are always present, so this is not colour-only information (P3, F19).

## Accessibility / Usability

Practical checks, from the live DOM:

- **Every button and link has a name** (measured `nameless: []` on all 47 captures).
- **Icon-only controls carry `aria-label`**: the ⋯ menus ("More options for X"), the reorder arrows
  ("Move X up/down"), the theme control (`aria-label` + `aria-pressed`).
- **Heading structure** is `h1` → `h2` → `h3` on every screen, with two duplicated labels (F10).
- **Labels on inputs**: the add screen's field, the destination `select`, the day fields, the bedtime
  fields and the source field all have a visible label; the audit found no unlabelled input.
- **Focus visibility**: `:focus-visible` is defined globally with a 3px outline and 2px offset; the
  audit did not tab through every screen (documented limitation) but the dialogs focus their first
  control on open.
- **Touch targets**: 40px minimum, one violation (F1).
- **Status is never colour-only**: badges are words, the pill has text, the progress bar has an
  `aria-label`.
- **Errors** are text, not icons, and are announced through `role="status"` toasts and inline
  `.field__error` paragraphs.

## Loading / Empty / Error States

| State | Screenshot | What happened | Does the parent understand it? | What can they do next? |
|---|---|---|---|---|
| Empty library | `empty-library` **(simulated)** | The catalog read was intercepted to return an empty library — no device state was touched, and the capture is labelled as such | Yes: "Your library is empty — Add a YouTube video or playlist to get started." + a filled "Add to library" | Add something |
| Empty category | `empty-category` (real) | Created for the audit, removed afterwards | Yes: "Nothing here yet — Add a playlist or video to this category." + "Add to library" | Add something |
| Loading library | measured during the load (929–1233 ms) | The shell paints first, then three skeleton rows and "Loading what your child can watch…" | Yes | Wait; the TV is being asked |
| Loading content (playlist resolve) | observed during the add flow | Inline spinner + "Looking this up on YouTube…" | Yes | Wait |
| Failed artwork | `error-artwork-unavailable` | `GET /catalog/artwork` blocked while the TV is fine | **No — this is F4**: the library says "Your TV does not have the latest yet" although the pill reads "TV on" and the TV is up to date | A parent would press "Update the TV now", which does nothing useful |
| Network unavailable | `error-state` | All three reads blocked | Partly: the pill reads "TV offline" and the Now Playing row says "Unable to reach the TV. [Try again]" | Try again (correct) |
| TV unavailable | `now-playing-unreachable` | `/status` blocked | Yes: "Unable to reach the TV." is distinct from "Nothing is playing right now" | Try again |
| Catalog conflict | `conflict-state` | A write behind the page's back | Mostly: "The library changed somewhere else … so this change was not saved. Nothing on the TV changed." — but it also says "(the TV is now on version 59)" (F6) | "Load the latest and start again" / "Keep my change and save again" |
| Unplayable content | `authorization-state` | The existing hidden/unplayable video | Yes: "Can't play yet" badge + the category banner offering to allow the source | Allow the source |
| Server error (400/409/502) | `error-unresolvable-link`, `error-channel-link` | Real refusals from the TV | Mostly: the 400 sentence is clear (see F3); the 502 falls back to "That link could not be read." (F15) | Try a different link |

## Console / Network Observations

Recorded while walking the whole dashboard (CDP `Log`, `Runtime.exceptionThrown`,
`Network.responseReceived`, `Network.loadingFailed`):

**Console** — three entries in the entire walk, all `error` level, all `source: network`, all caused
by this audit's own deliberate probes:

```text
502  POST /catalog/import/resolve   (the unresolvable-video-id probe)
400  POST /catalog/import/resolve   (the channel-link probe)
409  PUT  /catalog                  (the conflict probe)
```

No JavaScript error, no uncaught exception, no CSP violation, no failed asset load, and no
accessibility warning was reported on any screen, in either theme, at any width. The dashboard handles
all three refusals without a console error of its own.

**Network** — no unexpected, duplicated or unnecessary request was observed:

- the dashboard's own reads are `/status` (polled: 5 s playing, 10 s paused, 20 s otherwise, silent
  while hidden), `/catalog`, `/catalog/artwork`, `/playlists`, `/time-limits`, `/stats`,
  `/stats/recent`, `/crash-log` — each once per page load except the poll;
- navigating between screens makes **no** request at all (the library document and artwork are already
  in memory; this is why navigating the library is instant);
- the same artwork URL was fetched four times in the run, once per Now Playing card rebuild (each
  served from cache), which is the card being rebuilt on a state-shape change rather than a repeated
  download;
- two `200 Image data:image/svg+xml;base64,…` entries appeared; they are browser-generated (no such
  URL exists in the dashboard's own code or in any response), most likely Chrome's placeholder for a
  lazy image that had not been painted yet. Cause not conclusively identified, and no product request
  is involved;
- two `FAILED Fetch` entries and the 4xx/5xx entries above are this audit's block lists and probes.

**Performance (observational, measured)** — cold page load to a usable library: 1233 ms (360px),
929 ms (390px), 1149 ms (412px), 1160 ms (1280px), 1129 ms (1440px). Dark-mode switching is
immediate (attribute + tokens, no reload). Resolving a 50-video playlist in the add flow took several
seconds on this TV (not measured precisely; the inline spinner is shown throughout). Nothing appeared
slow enough to warrant a change, and no performance work is proposed for W8.2.

## Copy / Terminology

Every questionable phrase found, with the recorded recommendation (nothing was changed):

```text
CURRENT:       "A whole channel is not a shelf. Allow that channel in Settings, then add the videos
                or playlists from it that you want your child to see."
PROBLEM:       "shelf" is the pre-W8 word for what the interface now calls a "category"; the sentence
                uses a term the parent has never seen, in the place they are already stuck.
RECOMMENDATION: "A whole channel can't be added here. Allow that channel in Settings, then add the
                videos or playlists you want from it."
SEVERITY:      P1
SOURCE:        server copy in CatalogImportRoutes.kt, surfaced verbatim by the dashboard

CURRENT:       "This removes it and the 0 items inside it" / "This removes it from your child's
                SafeTube library, on this phone and on the TV."
PROBLEM:       The same collection says "50 videos" two lines above, and removing the container
                removes those 50 videos from the TV. The confirmation understates the consequence.
RECOMMENDATION: Count what the child can reach, not what the document holds: "This removes it and
                the 50 videos inside it."
SEVERITY:      P1

CURRENT:       "Another phone or browser saved a change first (the TV is now on version 59)"
PROBLEM:       A catalog version number is an internal counter; a parent has no use for it.
RECOMMENDATION: Drop the parenthetical.
SEVERITY:      P2

CURRENT:       "Your TV does not have the latest yet"
PROBLEM:       Shown when the artwork read fails even though nothing is stale (F4).
RECOMMENDATION: Only claim staleness when the installed version is actually known and older.
SEVERITY:      P2

CURRENT:       "1 category · 1 collection · 1 video" (library) vs "50 videos" (the collection row)
PROBLEM:       Two counts of the same library that disagree.
RECOMMENDATION: Count the videos the child can reach, on both screens.
SEVERITY:      P2

CURRENT:       "Move to another category… Keeps the video and everything inside it"
PROBLEM:       Shown for collections too; "the video" is wrong for a container.
RECOMMENDATION: "Keeps everything inside it."
SEVERITY:      P2

CURRENT:       "Added by you · hidden · can't play yet" + badges [Hidden] [Can't play yet]
PROBLEM:       The same two facts are stated twice in one row.
RECOMMENDATION: Keep the badges, drop them from the meta line.
SEVERITY:      P2

CURRENT:       "Minutes allowed each day. Use −1 for no limit."
PROBLEM:       A negative number is a developer convention for "no limit".
RECOMMENDATION: A "No limit" switch per day, or a blank field meaning no limit.
SEVERITY:      P2

CURRENT:       "It will pick the change up on its own, or you can send it now." / button "Update the TV now"
PROBLEM:       Two verbs for one action in the same block ("send" vs "update").
RECOMMENDATION: One verb: "or you can update it now."
SEVERITY:      P3

CURRENT:       "Playlist · 50 videos ready"
PROBLEM:       "ready" is vague about what is ready.
RECOMMENDATION: "Playlist · 50 videos allowed to play".
SEVERITY:      P3

CURRENT:       "SafeTube 0.10.0 on the TV" (in the TV section)
PROBLEM:       An app version is support information, not a parent setting.
RECOMMENDATION: Move it to Troubleshooting beside the error report.
SEVERITY:      P3

CURRENT:       "That link could not be read." (for a video that does not exist)
PROBLEM:       Generic fallback where a specific sentence exists in the code.
RECOMMENDATION: Map the TV's 502 for an unknown id to "Nothing was found at that link."
SEVERITY:      P3

CURRENT:       "What has been watched" as both the group heading and the card title
PROBLEM:       The same words at two heading levels; also "TV" group over "Your TV" card.
RECOMMENDATION: Give the card a distinct title ("Recent videos").
SEVERITY:      P3

CURRENT:       "Add to library" as breadcrumb, page title and primary button
PROBLEM:       One label in three roles on one screen.
RECOMMENDATION: Title the screen "Add from YouTube" (the crumb can stay).
SEVERITY:      P3

KEEP AS IS:    "Only what is listed here can play on the TV. Adding something to your library does
               not allow it." — the clearest statement of the security model in the product.
KEEP AS IS:    "It does not delete anything from YouTube."
KEEP AS IS:    "Nothing is playing right now" / "Unable to reach the TV." / "The TV stopped reporting
               what it is playing." — three states, three distinct sentences.
KEEP AS IS:    "These videos come from the TV … there is nothing to list here."
```

## Visual Polish

**Typography.** One scale, three weights (700 titles, 600 emphasis, 400 body), 26px `h1`, 19px card
titles, 16px body, 14px secondary, 13px meta, 12px badges. Line height is comfortable; nothing
measured below 12px; no truncation was observed (titles wrap).

**Spacing.** Consistent 12–16px rhythm: 16px page margins, 12–14px card padding, 12px between cards.
The Now Playing card, category card and settings cards share the same radius, border and shadow.

**Cards.** Two kinds, coherently used: `.tile` for categories (no artwork by design) and `.row`/`.card`
for collections and videos (16:9 artwork, 104px on mobile, 140–160px on desktop).

**Buttons.** A clear hierarchy: one filled primary per screen, neutral secondaries, a red-tinted
destructive in menus and confirmations, quiet links for "Try again"/"Not now". Disabled buttons
(reorder arrows at the ends of a list) are visibly dimmed and are `disabled`, not just styled.

**Icons.** Emoji and glyphs only (🌙/☀️, ▲▼, ⋯, ▶, 🎬, ⚙️, 📺, 📼). They render consistently across the
platforms tested, but they are the weakest part of the visual system: their size, baseline and colour
are set by the font, so the theme toggle, the tiles' arrows and the Now Playing glyphs sit at slightly
different optical weights, and a platform without an emoji font would show boxes. This is a known
trade-off (no icon font is loadable under the content policy); a small inline-SVG set would fix it if
a future phase wants to.

**Artwork.** 16:9 everywhere (measured 1.76 on the sample), `object-fit: cover`, rounded to 10px on
rows, lazy and asynchronous, `alt` = the item's title, and an error fallback to a placeholder instead
of a broken-image glyph. Only two things can show artwork on the library screen: the Now Playing card,
and any collection rows once a category is opened.

**Density.** On a phone: 20 visible lines on the library with one category. On desktop: the same 20
lines in a 728px column with large empty margins either side.

## Product Mental Model

What a new parent would infer, compared with what the UI communicates:

| Concept | Intended model | What the UI actually says | Match |
|---|---|---|---|
| Library | "What my child can watch" | "Your library", counts, categories, "Add to library" | ✅ |
| Category | "A group of things" | A card with counts and a chevron; "New category"; "Add a playlist or video to this category" | ✅ |
| Collection | "A playlist/show" | Opens to a list of videos, or explains that the TV keeps the videos itself | ✅ |
| Add | "Put something into the library" | One link field, a preview, a destination, a permission switch | ✅ |
| Playback | "What the TV is currently doing" | A card that says playing/paused/idle/unreachable with a live playhead | ✅ |
| Permission | "Whether my child may watch it" | "Allowed YouTube sources" + "can't play yet" + "Let my child watch it" | ✅ |
| Settings | "How SafeTube behaves" | Six named groups | ✅ |
| Library counts | — | The summary and the category screen disagree (F5) | ❌ |
| Removing a collection | — | The dialog counts 0 items for a 50-video collection (F2) | ❌ |
| TV freshness | — | A picture failure is reported as a stale TV (F4) | ❌ |

The model holds. The three mismatches are all **numbers and consequences**, not concepts — which is
why they are worth fixing: they are the only places where the dashboard tells a parent something that
is not what the product will do.

## Findings

| ID | Severity | Area | Current Behavior | Problem | Evidence | Recommendation |
|----|----------|------|------------------|---------|----------|----------------|
| F1 | P1 | Mobile / Navigation | The breadcrumb "Your library" measures 74×28 px on every child screen | The only tap target below the 40px minimum, and it is the "go back" control | `measures.json`: `small[]` non-empty on 14 captures; `category-mobile`, `collection-mobile`, `add-content-mobile` | Give `.crumb` a 40px minimum height (padding, not font size) |
| F2 | P1 | Collection / Delete | The collection menu says "Removes this and the 0 items inside it"; the confirmation says only "This removes it" | Removing the container removes the 50 videos the child sees; the copy understates it twice | `item-menu-mobile`, `delete-confirm-mobile`, `collection-mobile` ("50 videos") | Count what the child can reach and say it in both places |
| F3 | P1 | Copy / Add flow | Refusing a channel link says "A whole channel is not a shelf." | "Shelf" was renamed to "category" in W8; the word no longer exists in the UI | `error-channel-link` (text captured verbatim) | Reword without the metaphor |
| F4 | P2 | Error states | Blocking only `GET /catalog/artwork` (TV up, pill "TV on") shows "Your TV does not have the latest yet" | A picture failure is reported as a stale TV, prompting a pointless action | Isolated A/B test: after `POST /catalog/refresh` → no banner; block artwork only → banner, pill "TV on"; `error-artwork-unavailable` | Treat an unknown installed version as unknown, not as zero |
| F5 | P2 | Library / Collection | Library summary "1 category · 1 collection · 1 video"; the collection row says "50 videos" | Two different counts of the same library | `library-*` and `category-mobile` texts | Count reachable videos in the summary too |
| F6 | P2 | Conflict | "…saved a change first (the TV is now on version 59)" | Exposes an internal version counter | `conflict-state` | Drop the parenthetical |
| F7 | P2 | Authorization state | A row reads "Added by you · hidden · can't play yet" plus badges "Hidden" and "Can't play yet" | The same two facts stated twice in one row | `authorization-state` | Keep the badges, drop them from the meta line |
| F8 | P2 | Item menu | "Move to another category… Keeps the video and everything inside it" appears for collections | Wrong noun for a container | `item-menu-mobile` | "Keeps everything inside it." |
| F9 | P2 | Desktop | The content column is 728px at both 1280 and 1440 | Wide screens show identical content with more empty margin; the layout is capped rather than designed for the width | `measures.json`: identical `h1` box (728px) at 1280 and 1440; `library-1280-light`, `library-1440-light` | Let the tiles grid (or a two-column library + Now Playing layout) use up to ~1100px |
| F10 | P3 | Settings | "What has been watched" is both the group heading and the card title; "TV" over "Your TV" | Duplicated label at two heading levels | `settings-mobile` headings | Rename the card ("Recent videos") |
| F11 | P3 | Settings | "SafeTube 0.10.0 on the TV" in the TV section | Support information in a parent section | `settings-mobile` | Move to Troubleshooting |
| F12 | P3 | Copy | "you can send it now" beside a button reading "Update the TV now"; "50 videos ready" | Two verbs for one action; a vague adjective | `now-playing-unreachable` (banner text), `settings-mobile` | Unify the verb; say what "ready" means |
| F13 | P3 | Add flow | "Add to library" is the breadcrumb, the page title and the primary button | One label in three roles | `add-content-mobile`, `add-content-preview` | Title the screen "Add from YouTube" |
| F14 | P3 | Library | With nothing playing the library shows no pictures at all (categories carry none, by design) | The first screen is text-only apart from emoji — the least "consumer app" surface | `library-360-light` (`images: []`) | Optional: a small tinted glyph or initial per category card, without adding pictures to the TV |
| F15 | P3 | Add flow | An unresolvable video id yields "That link could not be read." | A friendlier mapping exists in the code but was not reached | `error-unresolvable-link` | Map the TV's 502 for an unknown id |
| F16 | P3 | Now Playing | The header pill and the card carry the same sentence while something plays | Deliberate (answer from any screen + detail on the library) but duplicated | `now-playing-playing`, `now-playing-desktop` | Keep, or shorten the pill to state only |
| F17 | P2 | Settings | Daily limits are numeric fields where "−1" means no limit | A developer convention in a parent form | `settings-mobile` ("Use −1 for no limit") | A "No limit" switch per day |
| F18 | P3 | Settings | No pointer to resetting the TV's PIN | A parent who has forgotten the PIN has no in-dashboard hint | `settings-mobile` (no such control) | One line in Troubleshooting: where the PIN comes from |
| F19 | P3 | Dark mode / Icons | Status accents are emoji/glyph driven (▶ ❙❙ 🌙 ☀️ ▲ ▼ ⋯) | Optical weight is set by the platform's emoji font; a platform without one shows boxes | `now-playing-playing-dark`, `settings-desktop-dark` | Optional: a small inline-SVG icon set (no icon font needed) |
| F20 | P3 | Navigation | Removing a category while inside it briefly shows "That category is gone" before redirecting | Slightly abrupt, though correct and safe | observed during the walk (no capture; text captured in the W8 run) | Redirect straight to the library when the current node disappears |

## Quick Wins

Each of these is small, local, and needs no architectural change:

1. **F1** — give `.crumb` a 40px minimum height (CSS only).
2. **F3** — reword the channel refusal (one string in `CatalogImportRoutes.kt`).
3. **F6** — drop "(the TV is now on version N)" (one string in `app.js`).
4. **F8** — "Keeps everything inside it." (one string).
5. **F10, F11, F12, F13** — four small copy/label edits.
6. **F15** — add the "not found" pattern to the existing error translator.
7. **F2** — count reachable videos in the Remove copy and the confirmation (one helper already exists:
   `containerVideoCount`).
8. **F5** — use the same helper for the library summary.

## Larger UX Improvements

Worth a phase of their own, in the order I would take them:

1. **F4** — make "your TV is behind" a claim the dashboard only makes when it knows the installed
   version. This is a correctness issue in what the product tells a parent, and it affects the
   freshness model the Now Playing card also depends on.
2. **F9** — decide what a wide screen is for. The cheapest honest answer is to let the tiles grid
   reach ~1100px and keep lists single-column; a richer one is a two-column library (Now Playing and
   the library side by side) above 1100px.
3. **F14 + F19** — a small visual pass: an inline-SVG icon set and a per-category accent, so the
   library stops being text-only and the glyphs stop depending on the platform emoji font. This is
   the only change that would move the product's "consumer app" impression, and it is entirely
   cosmetic.
4. **F17** — a "No limit" control instead of the −1 convention in the screen-time form.

## Things That Should NOT Be Changed

- **The Now Playing card's structure and its five states.** It is the audit's clearest success: the
  right size, honest about freshness, and it never confuses "nothing playing" with "cannot reach the
  TV". The pill beside it is deliberate duplication.
- **The two-section navigation** (Library / Settings) and the hash routes. No dead ends were found.
- **"Allowed YouTube sources" and its explanation.** It is the security model in one parent sentence.
- **The removal confirmation's YouTube sentence.** "It does not delete anything from YouTube." is the
  single most valuable line in the destructive flow.
- **The reorder arrows plus the spelled-out menu.** Discoverable without being a drag gesture.
- **Category cards having no picture** (W6.1's rule on the parent's screen) — the audit does not
  recommend giving categories artwork; it recommends that the *card* look less bare (F14).
- **The content policy and the no-framework, no-build-step decision.** The audit found no console
  error, no CSP violation and no dependency that needs changing.
- **The empty-state copy for a collection whose videos live on the TV.** "These videos come from the
  TV…" is the right answer to a genuinely confusing situation.

## W8.2 Recommendation

**A small number of high-value UX changes — not a polish pass, and not "no changes".**

The evidence says the dashboard has crossed the line the brief cares about: it is understandable
without documentation, and its remaining defects are *specific statements that are not true or not
clear* rather than missing structure. Three of those statements are worth fixing immediately because a
parent acts on them (F2 the removal count, F3 the stale term, F1 the 29px back control), and two more
because they misreport the state of the world (F4 the false staleness, F5 the disagreeing counts).

Concretely, W8.2 should contain: **F1–F8 and F15** (two of them are one-line CSS/string changes, the
rest are local edits in the dashboard and in one server string), a decision on **F9** (desktop width),
and **no** restructuring of navigation, cards, the Now Playing card, the catalog architecture or the
content policy. F10–F14 and F16–F20 are recorded for a later polish pass and should not delay this one.

No overall score is offered, deliberately: the findings above are the measure.

---

## Audit Actions and State Restoration

What the audit did to the world, and how it was undone:

| Action | Why it was necessary | Restored? |
|---|---|---|
| `adb shell settings put secure screensaver_enabled 0`, `screen_off_timeout 1800000`, `svc power stayon true` | the project's own device workflow needs them to keep the TV awake during a run | yes — 1 / 900000 / off, verified |
| `adb forward tcp:8080 tcp:8080` | to reach the TV's dashboard from the browser | yes — `forward --remove-all` |
| Created two categories ("Bedtime stories …", "Audit Empty") | to observe a long name and an empty category; no way to see those states without real data | yes — removed, and the document is byte-identical to the baseline at the end (`restored: 3 nodes, identical to baseline`) |
| `PUT /catalog` with the *same* nodes (conflict probe) | to make the page's own write stale, which is the only way to see the conflict dialog | yes — the same nodes were written, only the version counter moved |
| Started and paused real playback, and pressed Stop | to observe the Now Playing states | yes — playback stopped; the TV is idle (verified `currentlyPlaying: null`) |
| `Fetch` interception of `GET /catalog` for one read | to see the empty-library state without emptying the parent's library | yes — interception disabled; no device state was involved |
| `Network.setBlockedURLs` for `/status`, `/catalog*`, `/playback/*` | to see the error and unreachable states | yes — all block lists cleared |
| One `POST /catalog/refresh` (in the isolated F4 test) | to bring the TV up to date so the banner test had one variable | no restore needed — this is the product's own "update the TV" action |

Nothing else was changed: no project file, no test, no database schema, no API, no committed
screenshot. One artifact of the audit method is worth recording: when the harness closed a dialog with
the browser's native `dialog.close()` instead of the app's own close path, the dialog element stayed in
the DOM (hidden). That is a harness effect, not a product defect — a parent closes dialogs through the
app's buttons or Escape, both of which remove the element.

## Regression Check

Run after the audit, with the working tree clean and no source change:

```text
JVM                 846 / 846 debug, 846 / 846 release   (BUILD SUCCESSFUL, 0 failures)
Dashboard JS         73 / 73 editor, 29 / 29 import, 33 / 33 ui   = 135 / 135
PLAYER tier          41 / 41   (test-results/tv/2026-09-26-222245)
FULL tier            56 / 56   (test-results/tv/2026-09-26-223105)
```

These establish that the audit disturbed nothing.

## Git Safety

```text
git status --short   →  only docs/W8_2_UX_AUDIT.md and docs/screenshots/w8_2/ (34 files)
git diff --stat      →  no tracked file modified
git rev-parse HEAD   →  2b14b89e51664c7e1203dbe6bef345479f85c319 (unchanged)
```

Nothing was committed.
