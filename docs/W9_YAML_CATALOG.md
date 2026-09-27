# The catalog file (W9)

A parent can download their whole library as one readable file and load one back. The file is a
versioned YAML document describing the curated catalog — the shelves, what is inside them, the order
and what is hidden — and nothing else.

Written by `tv-app/app/src/main/assets/catalog-yaml.js`, offered on the dashboard's **Settings →
Content** card, and proved by `tv-app/scripts/dashboard-catalog-yaml.test.js`.

---

## 1. What the file is, and what it is not

| It is | It is not |
|---|---|
| The curated library: categories, collections, single videos, their order, their hidden state | Permission. Allowed sources are a different thing, stored elsewhere, and are never written to or read from this file |
| A file a human can read and edit by hand | A backup of the TV. Watch history, resume positions, time limits and the used-minutes balance are not in it |
| A stable identity: every category, collection and video keeps its id | A second library. There is one tree; importing replaces it in a single atomic write |

**The one invariant.** Importing a file cannot make anything playable. A video plays when the playlist
or video it came from is an *allowed source*, and that list is not in this file: an imported video the
TV has no approval for appears in the child's library and is marked **Can't play yet**. The live
verification imports a file containing a video nobody approved and asserts the allowed sources are
byte-identical before and after, and that the dashboard's own count of unplayable videos grew by
exactly one.

## 2. The format

```yaml
version: 1

catalog:
  name: SafeTube Library
  categories:
    - id: cat-cartoon
      name: Cartoon
      order: 0
      picture: video:i-demo-video      # optional: the still a collection shows
      videos:                          # videos directly on the shelf
        - id: i-demo-video
          youtube: DuXwFlL8Usk
          title: Disabled Demo
          order: 1
          hidden: true
      collections:
        - id: i-cocomelon
          name: CoComelon
          order: 0
          playlist: PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT
          videos:
            - id: i-cocomelon#e_04ZrNroTo
              youtube: e_04ZrNroTo
              title: Wheels on the Bus
              order: 0
              playlist: PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT
```

* `version` is the format's own version. A file whose version this build does not read is refused
  whole, with the reason, and nothing is written. It exists so a future format can change without
  silently importing a file it half-understands.
* `id` is the catalog's own identity for a node. Keeping it is what makes a round trip exact: the TV
  keeps the same rows, so a resume position or a chosen still stays attached to the same video.
* `order` is the position among that container's children. Gaps and duplicates are repaired on
  import — siblings are renumbered `0..n-1` in the order the file lists them, which is what makes a
  hand-edited file with one line deleted still import.
* `hidden: true` is the same thing the dashboard calls hidden: the node stays in the library and the
  child does not see it.
* `playlist` on a **collection** is the Youtube playlist it imports. On a **video** it records where
  that video came from — the provenance that decides whether it may play.
* `picture: video:<id>` chooses the still a container shows; `auto` (the default) is left out.
* `name` is a human label. It is echoed in the preview and is not stored: the catalog model has no
  library name.

### The supported YAML

Deliberately a subset, so that a reader this small can refuse everything it does not understand
rather than guess: block mappings and sequences, plain and quoted scalars, `#` comments, and `[]`/`{}`
for empty. Flow collections (`{a: 1}`), anchors and aliases, tags, multi-line blocks (`|`, `>`), tabs
for indentation and multi-document files are all **refused with a line number**. Keys that are not
part of the format are refused by name — a mistyped `folder:` is a mistake worth naming, not a line
to drop in silence.

## 3. Export

Read-only. It serialises the working copy, hands the browser a `safetube-catalog.yaml` download, and
touches neither the server nor the TV — the live verification asserts the catalog version is unchanged
afterwards.

## 4. Import, in the order it happens

1. The parent picks a file. `CatalogYaml.readDocument` parses and validates it whole.
2. If anything is wrong, a dialog names the problems ("Line 4: tabs are not allowed for indentation -
   use spaces", `Category "One": "folder" is not something a SafeTube catalog file has`) and says
   plainly that nothing was changed. No write is attempted.
3. If it is valid, a **preview** appears before anything is written: the shape of the file as an
   outline, how many categories, collections and videos it holds, what would change compared with the
   library on screen (added, removed, renamed, reordered, hidden, shown again), and — if any of its
   videos come from a source that is not allowed — that those videos will not play yet.
4. Only **Import catalog** applies it, through `publish()`: one `PUT /catalog` carrying the whole
   tree at the catalog version this page read. A stale version, a refusal from the server, or a lost
   connection leaves the previous library exactly as it was. A cancelled preview writes nothing.
5. The TV picks the new library up through the existing synchronization, which the dashboard
   triggers immediately rather than leaving the parent to wait fifteen minutes.

## 5. What is verified, and where

| Claim | Where it is proved |
|---|---|
| The format, validation, preview rules, refusals, round-trip stability, 2000+ nodes in under 5s | `tv-app/scripts/dashboard-catalog-yaml.test.js` (36 tests) |
| The file has no DOM, no network, no storage, and no way to grant playback | the guard tests at the end of the same suite |
| The panel, the export, the preview gate, the single write path, the words a parent reads | `tv-app/scripts/dashboard-catalog-ui.test.js` (7 W9 guards) |
| The route and the shipped asset | `DashboardRoutesTest`, `DashboardAssetTest` |
| The real round trip on a real TV: export → import → identical tree, one atomic write, the TV on the new version, an edited file behaving as written, invalid files refused, allowed sources untouched, the library restored | the live W9 harness against the Mi Box (`docs/TESTING.md`) |
