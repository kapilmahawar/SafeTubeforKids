# The example kids library

A deterministic, loadable library of real children's videos from four official YouTube channels, so
that a test, a demo or a support call can start from the same shelves every time instead of from
whatever a family happens to have configured.

* Fixture: `tv-app/scripts/fixtures/example-kids-library.yaml` (the catalog file a parent imports)
* Manifest: `tv-app/scripts/fixtures/example-kids-library.json` (the same library as data, with the
  name every item is referred to by in tests)
* Generator: `tv-app/scripts/fixtures/build-example-library.js`
* Loader: `tv-app/scripts/fixtures/load-example-library.js`
* Device verification: `tv-app/scripts/tv-e2e.ps1 -Tier example`

Last verified: **2026-09-28**, on the Mi Box 4 (`MIBOX4`, Android 12, 1920×1080 @ 320 dpi), commit
`eeb367a`, by `tv-e2e.ps1 -Tier example -SkipBuild` run `test-results/tv/2026-09-28-162202`
(**22/22 checks PASS**), with all five sources below allowed, the catalog at the version the server
stored, and 0 unresolved items reported by the resolver.

## What it is, and what it is not

It **is** a catalog file: the shelves, what is inside them, in what order, and which of them the child
may see. It is the same format the parent dashboard's *Library file* panel exports and imports.

It is **not** permissions. A video listed here plays on the TV only while the YouTube source it came
from is one of the family's allowed sources. Importing the file can never make something playable -
which is exactly what item `EXAMPLE_UNAPPROVED_VIDEO` in the fixture is there to prove: it is a real,
public, resolvable Peppa Pig video that is deliberately *not* in any allowed source, so a refusal when
a child presses it can only be the security boundary and never a broken link.

## What is in it

Seven categories, in this order - the order is part of the fixture, because the order is what the
child sees:

| # | category | id | what is in it |
|---|---|---|---|
| 0 | CoComelon | `ex-cocomelon` | a playlist-backed collection (105 items), plus one video of its own |
| 1 | Bluey | `ex-bluey` | three videos from the official channel |
| 2 | Peppa Pig | `ex-peppa` | a 17-item playlist-backed collection, plus the one unapproved video |
| 3 | ChuChu TV | `ex-chuchu` | a playlist-backed collection (269 items) and a long-titled video |
| 4 | Mixed Cartoons | `ex-mixed` | videos from three different channels **and** a playlist - the mixed row |
| 5 | One Video (edge case) | `ex-edge-one-video` | exactly one item |
| 6 | Empty Shelf (edge case) | `ex-edge-empty` | nothing at all |

46 nodes in total: 7 categories, 4 collections, 35 videos, 1 of them hidden.

### The sources, and how each was confirmed

Every id below was read from the channel's own material - its RSS feed or its official website - and
then resolved through the TV app's own YouTube resolver before anything was written down. Nothing here
was guessed or searched for at test time.

| channel | kind | id | items | used for |
|---|---|---|---|---|
| CoComelon - Nursery Rhymes | playlist | `PLb8WrhcvGhOjFm2xrfaUZq3ytKuWQL5wE` | 105 | CoComelon, Mixed Cartoons, One Video |
| Peppa Pig - Official Channel | playlist | `PLFEgnf4tmQe-SPm9PXEZSKldOCzzBVby9` | 17 | the playlist walk (open → first → advance → last) |
| Peppa Pig - Official Channel | playlist | `PLFEgnf4tmQe8dyOm2mZ8fSrZnwwIdoTAG` | 21 | Mixed Cartoons |
| ChuChu TV Nursery Rhymes & Kids Songs | playlist | `PLV-cxl3VSwWHa83pPHIealm1vg0Cdp3aZ` | 269 (200 cached) | ChuChu TV |
| Bluey - Official Channel | channel | `UCVzLLZkDuFGAE2BGdBuBNBg` | 3 of its uploads | Bluey, Mixed Cartoons |

* The CoComelon and ChuChu TV playlist ids come from those channels' own video descriptions
  (`youtube.com/feeds/videos.xml?channel_id=…`), the Peppa Pig ones from the official channel's
  descriptions, and the Bluey channel id from wikidata (`Q39071378`, property `P2397` = YouTube channel
  ID), confirmed by fetching that channel's RSS feed and finding "Bluey - Official Channel".
* Bluey has no official playlist this could confirm as the channel's own, so its items are approved
  through the **channel** instead. That is a supported source kind, and it is also the reason for the
  one real limitation recorded at the bottom of this page.
* The resolver reports the size it actually read; the manifest records it, so a playlist that changes
  upstream shows up as a difference between the file and the TV rather than silently.

### Every item, with the name tests use

`EXAMPLE_*` is the manifest's name for an item; the node id is the catalog's own identity for it.

| name | type | YouTube id | title | source | category | pos | shown | expected |
|---|---|---|---|---|---|---|---|---|
| `EXAMPLE_COCOMELON_PLAYLIST` | SUBCATEGORY | `PLb8WrhcvGhOjFm2xrfaUZq3ytKuWQL5wE` | CoComelon Animal Songs | CoComelon - Nursery Rhymes | CoComelon | 0 | yes | OPENS_CONTAINER |
| `EXAMPLE_COCOMELON_VIDEO_1` | VIDEO | `MTqaxmylG8o` | NEW! Fireflies ✨ JJ and Boba's Magical Forest Adventure! 🌲 \| CoComelon Animal Time - Kids Songs | CoComelon - Nursery Rhymes | CoComelon | 0 | yes | PLAYS |
| `EXAMPLE_COCOMELON_VIDEO_2` | VIDEO | `GsrCSM_agk0` | Apples & Bananas 🍎 Which is Best?! \| NEW 🍌 CoComelon Animal Time \| Animals for Kids | CoComelon - Nursery Rhymes | CoComelon | 1 | yes | PLAYS |
| `EXAMPLE_COCOMELON_VIDEO_3` | VIDEO | `TP_nMzQvw7k` | Happy & You Know It 🌊 JJ's Obstacle Course \| NEW ⭐ CoComelon Animal Time \| Animals for Kids | CoComelon - Nursery Rhymes | CoComelon | 2 | yes | PLAYS |
| `EXAMPLE_COCOMELON_HIDDEN` | VIDEO | `mtG2Ng97QJw` | Rain Rain Go Away ☔ \| NEW 🐜 CoComelon Animal Time \| Animals for Kids | CoComelon - Nursery Rhymes | CoComelon | 3 | **no** | HIDDEN_FROM_THE_CHILD |
| `EXAMPLE_COCOMELON_VIDEO_4` | VIDEO | `Eqo0U_VkhR0` | Wheels on the Bus Lullaby! 🚍 \| NEW ⭐ CoComelon Animal Time \| Animals for Kids | CoComelon - Nursery Rhymes | CoComelon | 1 | yes | PLAYS |
| `EXAMPLE_BLUEY_VIDEO_1` | VIDEO | `7PBDE0051wc` | FULL EPISODES 💙 🍼 Baby Bluey Rolls Over! 🥹 \| Bluey | Bluey - Official Channel | Bluey | 0 | yes | PLAYS |
| `EXAMPLE_BLUEY_VIDEO_2` | VIDEO | `mNt8QH-fyyY` | FULL EPISODES 💙 🖍️ Creating a Hotel with Bluey and Bingo ✨ \| Bluey | Bluey - Official Channel | Bluey | 1 | yes | PLAYS |
| `EXAMPLE_BLUEY_VIDEO_3` | VIDEO | `rX0NbuympHQ` | Bluey and Bingo Can't Speak! 🤫 \| Sibling Games 💙🧡 \| Bluey | Bluey - Official Channel | Bluey | 2 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST` | SUBCATEGORY | `PLFEgnf4tmQe-SPm9PXEZSKldOCzzBVby9` | Peppa Pig Birthday Special | Peppa Pig - Official Channel | Peppa Pig | 0 | yes | OPENS_CONTAINER |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_01` | VIDEO | `AwblSoFCdlY` | Happy Birthday to You Song with Peppa Pig \| Peppa Pig Official Family Kids Cartoon | Peppa Pig - Official Channel | Peppa Pig | 0 | yes | PLAYS_FIRST_ITEM |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_02` | VIDEO | `IV8Iu-hoI-I` | Come Play with Peppa: Making a Chocolate Birthday Cake with Peppa Pig \| Family Kids Cartoon | Peppa Pig - Official Channel | Peppa Pig | 1 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_03` | VIDEO | `DGxlVLT8jXk` | 🎁 Peppa Pig Stop Motion: Shopping for George's Birthday Present \| Family Kids Cartoon | Peppa Pig - Official Channel | Peppa Pig | 2 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_04` | VIDEO | `6A0aiN0xOHg` | \| Peppa Pig Makes a Surprise Birthday Cake | Peppa Pig - Official Channel | Peppa Pig | 3 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_05` | VIDEO | `MUuWgxr5GGk` | Peppa Pig at Elephant Edmond's Birthday Party \| Peppa Pig Official Family Kids Cartoon | Peppa Pig - Official Channel | Peppa Pig | 4 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_06` | VIDEO | `XSwvlK5KWJg` | ⭐️ Peppa Pig Best Festival Special \| Peppa Pig Official Family Kids Cartoon | Peppa Pig - Official Channel | Peppa Pig | 5 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_07` | VIDEO | `NuE3HnVy2tg` | Peppa Pig Birthday Party Special | Peppa Pig - Official Channel | Peppa Pig | 6 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_08` | VIDEO | `BZWUsQKbWTE` | Glitter Party at Peppa Pig's Playgroup \| Peppa Pig Official Family Kids Cartoon | Peppa Pig - Official Channel | Peppa Pig | 7 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_09` | VIDEO | `iCb2Dw7okq8` | Celebrating Freddy Fox's Birthday with Peppa Pig | Peppa Pig - Official Channel | Peppa Pig | 8 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_10` | VIDEO | `maeAvp1-mL0` | Peppa Pig Helps Out at Edmond Elephant's Birthday Party \| Peppa Official Family Kids Cartoon | Peppa Pig - Official Channel | Peppa Pig | 9 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_11` | VIDEO | `3lG1uGNagdI` | 🎈 Peppa Pig Birthday Parties Special 🎂 | Peppa Pig - Official Channel | Peppa Pig | 10 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_12` | VIDEO | `GijfF9TLOUM` | 🎂 Peppa Pig Celebrates George Pig's Birthday | Peppa Pig - Official Channel | Peppa Pig | 11 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_13` | VIDEO | `QxNr3sT1wF8` | 🎂 Peppa Pig Celebrates Edmond's Birthday 🎂 | Peppa Pig - Official Channel | Peppa Pig | 12 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_14` | VIDEO | `bYAsdgxDyfY` | Peppa Pig's Best Birthday Party! | Peppa Pig - Official Channel | Peppa Pig | 13 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_15` | VIDEO | `yT9dkgucx7s` | Peppa Pig Birthday Compilation | Peppa Pig - Official Channel | Peppa Pig | 14 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_16` | VIDEO | `amNTw2cbxyY` | Peppa Pig's Birthday Compilation | Peppa Pig - Official Channel | Peppa Pig | 15 | yes | PLAYS |
| `EXAMPLE_PEPPA_PLAYLIST_ITEM_17` | VIDEO | `TsPK7iMZjR0` | Peppa Pig Official - Mummy Pigs Birthday | Peppa Pig - Official Channel | Peppa Pig | 16 | yes | PLAYS_LAST_ITEM |
| `EXAMPLE_UNAPPROVED_VIDEO` | VIDEO | `hyacEIXP3oc` | Peppa Pig Tales 2026 🍎 Peppa Learns to PACK Her SCHOOL LUNCHBOX! 🍱 BRAND NEW Peppa Pig Episodes | Peppa Pig - Official Channel | Peppa Pig | 1 | yes | PLAYBACK_DENIED |
| `EXAMPLE_CHUCHU_PLAYLIST` | SUBCATEGORY | `PLV-cxl3VSwWHa83pPHIealm1vg0Cdp3aZ` | ChuChu TV Classics | ChuChu TV Nursery Rhymes & Kids Songs | ChuChu TV | 0 | yes | OPENS_CONTAINER |
| `EXAMPLE_CHUCHU_VIDEO_1` | VIDEO | `4EpZ0QQJ2kE` | Christmas & Winter Songs Collection -  Holiday Fun Songs by ChuChu TV Nursery Rhymes #ChuChuTV100M | ChuChu TV Nursery Rhymes & Kids Songs | ChuChu TV | 0 | yes | PLAYS |
| `EXAMPLE_CHUCHU_VIDEO_2` | VIDEO | `fVyw-iZLRYw` | Healthy Habits for Kids 🍎 Morning to Night Routine Songs \| Good Manners, Hygiene & more by  ChuChuTV | ChuChu TV Nursery Rhymes & Kids Songs | ChuChu TV | 1 | yes | PLAYS |
| `EXAMPLE_CHUCHU_LONG_TITLE` | VIDEO | `vDKhfB0wLog` | The Colors Song 🌈 \| Learn Colors for Toddlers \| Orange Song 🍊 \| ChuChuTV Kids Songs & Nursery Rhymes | ChuChu TV Nursery Rhymes & Kids Songs | ChuChu TV | 1 | yes | PLAYS_TITLE_102_CHARS |
| `EXAMPLE_MIXED_BLUEY_VIDEO` | VIDEO | `7PBDE0051wc` | FULL EPISODES 💙 🍼 Baby Bluey Rolls Over! 🥹 \| Bluey | Bluey - Official Channel | Mixed Cartoons | 0 | yes | PLAYS |
| `EXAMPLE_MIXED_COCOMELON_VIDEO` | VIDEO | `pRn3fdmSY7w` | This is the Way Bedroom! Bedtime Routine Song 🛌🏻 \| NEW 😴 CoComelon Animal Time \| Animals for Kids | CoComelon - Nursery Rhymes | Mixed Cartoons | 1 | yes | PLAYS |
| `EXAMPLE_MIXED_PEPPA_PLAYLIST` | SUBCATEGORY | `PLFEgnf4tmQe8dyOm2mZ8fSrZnwwIdoTAG` | Peppa Pig Best Videos | Peppa Pig - Official Channel | Mixed Cartoons | 2 | yes | OPENS_CONTAINER |
| `EXAMPLE_MIXED_PEPPA_VIDEO_1` | VIDEO | `eqgYDiZZwg4` | Play Marble Run with Peppa Pig | Peppa Pig - Official Channel | Mixed Cartoons | 0 | yes | PLAYS |
| `EXAMPLE_MIXED_PEPPA_VIDEO_2` | VIDEO | `mHhGBv1GSvE` | Peppa Pig Visits the Hospital on the Christmas Day \| Peppa Pig Official Family Kids Cartoon | Peppa Pig - Official Channel | Mixed Cartoons | 1 | yes | PLAYS |
| `EXAMPLE_EDGE_ONE_VIDEO` | VIDEO | `iALurhct9h0` | Lights Out! JJ Isn't Scared of the Dark 🕯️😴 \| NEW 😴 CoComelon Animal Time \| Animals for Kids | CoComelon - Nursery Rhymes | One Video (edge case) | 0 | yes | PLAYS |
| `EXAMPLE_EDGE_EMPTY_SHELF` | CATEGORY | – | Empty Shelf (edge case) | – | Empty Shelf (edge case) | 6 | yes | NOT_RENDERED_ON_THE_TV |

## The edge states, and where each one lives

| edge state | how the fixture carries it | what a correct TV does |
|---|---|---|
| empty category | `ex-edge-empty`, a `CATEGORY` with no children | stores it, and draws **no row** for it (`CatalogUiProjection` drops a category with no cards) |
| one item | `ex-edge-one-video`, exactly one video | draws a row with one card, and NEXT on it ends the queue |
| many items | the CoComelon (105) and ChuChu (269, cached to 200) collections | opens a container whose card count equals the source's approved size |
| long titles | the real 102-character ChuChu title, plus 15 titles over 80 characters | renders and wraps them; every dump carries them intact |
| a large playlist | `PLV-cxl3VSwWHa83pPHIealm1vg0Cdp3aZ` at 269 items, and a 200-item channel | caches up to `MAX_VIDEOS_PER_SOURCE` (200) and says so |
| a hidden item | `EXAMPLE_COCOMELON_HIDDEN` (`hidden: true`) | stores the node, and never offers it to the child |
| an unapproved item | `EXAMPLE_UNAPPROVED_VIDEO` | shows the card, refuses to play it |
| a playlist and videos in one row | every brand row, and Mixed Cartoons most of all | draws both kinds of card in one row |

## How to load it

**As a parent would.** Open the dashboard, go to *Settings → Library file*, choose
`example-kids-library.yaml`, read the preview, press *Import*. Allow the sources the file names first
(*Allowed YouTube sources*); the preview says how many of its videos can play today and why.

**From a shell** (this is what the device tier does, and it is the same three calls the dashboard
makes):

```
node tv-app/scripts/fixtures/load-example-library.js --host <tv-ip> --pin <parent-pin>
```

It allows every source the file's items come from, `PUT`s the catalog, asks the TV to refresh, and
waits until the TV reports the version the server stored. `--replace-sources` also removes sources the
fixture does not name, which is what makes a run deterministic; without it, other sources are left
alone and reported.

**To reset or reload:** re-run the loader. It is idempotent - it re-allows what is already allowed,
replaces the catalog in one write, and syncs. To start from nothing at all instead, use the TV's own
destructive reset (*Settings → Reset SafeTube*, which needs the confirmation phrase and cannot be
triggered by any HTTP route).

**To regenerate the fixture** (after a playlist changes upstream, or to re-verify the ids):

```
node tv-app/scripts/fixtures/build-example-library.js --host <tv-ip> --pin <parent-pin>
```

This resolves each source through the TV app's own resolver, writes the YAML with the dashboard's own
serializer, reads it back with the dashboard's own parser, and refuses to write anything if the file
does not round-trip.

## How to verify it on a TV

```
cd tv-app/scripts
./tv-e2e.ps1 -Tier example -SkipBuild
```

The tier loads the fixture and then checks, without ever using touch input:

| check | what it proves |
|---|---|
| `EXAMPLE_LIBRARY_LOAD` | the loader completed and left the TV on the version the server stored |
| `EXAMPLE_LIBRARY_CATALOG_DOCUMENT` | the server holds all 46 nodes of the file |
| `EXAMPLE_LIBRARY_ROWS` | every node of the file that the dump could carry is on the TV with the same title, position, parent, type, video id and visibility |
| `EXAMPLE_LIBRARY_CATEGORY_ORDER` | the seven categories, in the file's order |
| `EXAMPLE_LIBRARY_SHELF_ORDER` | the rows the child sees, in the file's order, and no more rows than the file has visible categories |
| `EXAMPLE_LIBRARY_EMPTY_SHELF_NOT_DRAWN` | the empty category is stored and not drawn |
| `EXAMPLE_LIBRARY_HIDDEN_ITEM` | the hidden video is absent from its container and the container's card count is exactly one short of its source |
| `EXAMPLE_LIBRARY_DPAD_NAVIGATION` | every category row is reachable with the remote alone, and its leftmost card is the file's first card |
| `EXAMPLE_LIBRARY_PLAYBACK_FOUR_SOURCES` | one item from each of CoComelon, Bluey, Peppa Pig and ChuChu TV plays, from an allowed source |
| `EXAMPLE_LIBRARY_PLAYER_CONTROLS` | pause, play and seek move the player, and the playhead actually moves |
| `EXAMPLE_LIBRARY_BACK_LEAVES_PLAYER` | BACK stops playback and stays in the app |
| `EXAMPLE_LIBRARY_FOCUS_RESTORED` | after BACK the focused card is the one that was playing |
| `EXAMPLE_LIBRARY_CONTINUE_WATCHING` | the shelf exists, sits first, and its first card is the video just watched |
| `EXAMPLE_LIBRARY_PLAYLIST_OPEN` / `_FIRST_ITEM` / `_ADVANCE` / `_FINAL_ITEM` | the container opens with 17 cards, its first item plays, NEXT walks the queue in the file's order, and NEXT on the last item ends the queue |
| `EXAMPLE_LIBRARY_UNAPPROVED_DENIED` / `_MESSAGE` | pressing the unapproved card plays nothing and says so on screen |

Artifacts land in `test-results/tv/<timestamp>/`: `test.log`, `result.json`,
`example-library-load.log`, `example-library-rows.json`, `example-library-queue.txt` and screenshots.

## What is pinned, and what is not

**Pinned in the file:** every node id, every YouTube id, every title, every position, every
`enabled` flag, and which playlist each collection imports.

**Resolved live:** the *contents* of a playlist-backed collection. The file lists the items it knows,
and the TV's sync adds the rest of the approved cache to that container - which is why the ChuChu
collection shows 200 cards while the file lists 3. The file is a floor, not a ceiling. A YouTube
playlist can also gain or lose items upstream; the fixture is regenerated rather than repaired.

**Region-dependent:** YouTube availability varies by country. All five sources resolved from the test
network, but a video that is unavailable in a given region will resolve to nothing there, and the app
will say "Couldn't play this video" - which is a resolution failure, not a permission failure. The two
messages are deliberately different strings in the app.

## The two limitations this fixture ran into, and what became of them

Both were real product behaviour found while building the fixture. **W12 fixed both**, and the fixture
now writes what it always should have:

1. **A catalog file could not name a channel as a video's source.** The catalog contract validated
   `youtubePlaylistId` as a *playlist* id and refused a channel id (`UC…`) with `Could not find a
   video, playlist, or channel in this YouTube URL`, so the three Bluey items carried no source in the
   file and the loader had to read the manifest for the channel. W12 made that field be checked against
   the three kinds of source the app can allow - a playlist, a channel or a single video - on a **video**
   node (a *container* still names a playlist and nothing else, because that is what it imports). The
   Bluey items now name `UCVzLLZkDuFGAE2BGdBuBNBg` in the file itself, and the loader reads its sources
   from the file alone.
2. **The dashboard reported those items as unplayable.** `app.js` decided playability from
   `node.youtubePlaylistId` against a map that only ever contained playlists, so a channel-sourced video
   said *"Can't play yet — its YouTube source is not allowed for your child"* while that channel was an
   allowed source and the video played on the TV. The map now holds every allowed source and the check
   is made against all of them. Measured in a real browser before and after: the same library went from
   **"5 videos can't play yet, allow these 4 sources"** to **"1 video can't play yet"** - and the one
   left is the deliberately unapproved item this fixture exists to test with.

The field is still called `youtubePlaylistId`, which is now a historical name for "the YouTube source
this came from". Renaming it would be a wire-format change for no behavioural gain.

