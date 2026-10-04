# Project guide

Practical detail for parents running SafeTube, and for anyone picking the repository up. The
[README](../README.md) is the introduction; this file is the working manual.

## What runs where

| Piece | Runs on | Reached by |
|---|---|---|
| SafeTube app (library, player, settings) | The Android TV box | The television's remote |
| Parent dashboard (library editor, sources, apps, limits) | Nothing to install — the TV serves the page | A phone, tablet or computer on the same network, in a browser, at the address the TV shows |
| Local HTTP API | Inside the app, on the TV | The dashboard's own pages |

The TV is the server. There is no SafeTube account and no SafeTube-hosted service involved in browsing
the library or playing a video: the library lives in a database on the TV, and the page a parent uses is
served by the TV over the local network.

## The parent workflow, end to end

### 1. First run, on the TV

A fresh installation opens on a short setup: a welcome screen, then a "connect a phone" screen that shows
the address to open, then creating the **Parent PIN** on the TV's own keypad, then a **Recovery Code** to
write down. Nothing else is reachable until that is done.

The PIN is entered on the TV, not on the phone. There is no default PIN, no factory password and no
hidden account: if you have not created one, the app will tell you to finish setting up on the TV.

### 2. Sign in to the dashboard

On a phone or computer on the same network, open the address the TV displayed. Sign in with the Parent
PIN. The session belongs to that browser; signing out, changing the PIN, or using the dashboard's
"sign out everywhere" action invalidates it.

If the PIN is forgotten, the dashboard offers the way back in with the Recovery Code, which was shown
once at setup. The Recovery Code can also replace a forgotten PIN. Both are credentials: treat the
Recovery Code like a spare key and keep it out of a screenshot.

### 3. Build the library

The dashboard is where the library is built:

- **Sources.** A source is an approved YouTube channel, playlist or single video. A source must be
  approved before anything from it can play. Adding one twice is refused rather than duplicated, and
  every source is resolved from YouTube when it is added.
- **Catalog.** The catalog is the structure a child sees: categories, collections inside them, and the
  videos inside those. Videos can be imported from a YouTube link (the editor shows a preview before
  anything is written), or a whole library can be imported and exported as a file so a setup can be
  backed up or moved.
- **Order and visibility.** The parent's order is the child's order, and items can be hidden from the
  child without being deleted.

Sources and catalog are deliberately two different things, and this is the single most important idea in
the product: **being in the catalog is not permission to play.** See
[ARCHITECTURE_AND_SECURITY.md](ARCHITECTURE_AND_SECURITY.md).

### 4. Watch on the TV

The child's screen shows the curated library: rows of categories and collections with artwork, and a
Continue Watching row built from what has actually been watched. There is no search box, no
recommendations and no way to browse YouTube itself.

### 5. Continue Watching and resume

Playback position is recorded per video as it is watched. An item that was left part-way through can be
resumed from where it stopped; an item that was nearly finished starts again instead of resuming, and a
position below a small threshold is not offered at all. When an item is opened, the player starts it and
offers the choice of carrying on, so nothing is blocked waiting for an answer.

### 6. When something stops working

| Symptom | What it usually means | What to do |
|---|---|---|
| The dashboard will not load | The phone and the TV are not on the same network, or the app is not running on the TV | Check the app is open on the TV, and that both devices use the same network |
| "This video can't be played" on the TV | Its source is no longer approved, or the video is no longer in the approved cache | Approve the source again in the dashboard, or remove the item |
| A video that used to play will not play | The source may have been withdrawn, or the video may have been removed upstream on YouTube | Check the source is still listed as approved in the dashboard |
| Artwork is missing in the library | The thumbnail could not be fetched at the time | Not fatal: the item still plays. Refreshing the dashboard's view usually restores the picture |
| Captions are offered for a video that has none | Captions come from the video itself | The subtitles menu offers only what the video actually has |

Destructive actions, and what they cost:

- **Removing a source** stops its videos playing, including videos that are still listed in the library.
  The catalog entries stay; they simply cannot be played.
- **Resetting SafeTube**, from the TV's own settings, erases the library, the approved sources and the
  parent credentials, and returns the app to first-run setup. It asks for a typed confirmation phrase on
  the TV. There is no dashboard button that can do this, on purpose: an unauthenticated request must not
  be able to erase a child's library.

## The app whitelist and kiosk screen

The dashboard's apps page lists the apps installed on the TV. Marking apps as allowed, and enabling the
kiosk screen, gives a screen that offers only those apps. This is an in-app screen, not an operating
system lock: it is a convenience for a child's TV, not a way to stop someone who knows how to use the
box's own settings.

## Repository layout

```text
README.md                     Product introduction, screenshots, quick start, remote guide
docs/PROJECT_GUIDE.md         This file: parent workflows, layout, contributor onboarding
docs/ARCHITECTURE_AND_SECURITY.md   How the app is put together and the invariants that must hold
docs/TESTING_AND_DEVICE_VERIFICATION.md  Tests, tiers, and the rules for using a family device
docs/DEVELOPMENT_HISTORY.md   Milestones, defects, and how the project got here
docs/                         Engineering reports from each development phase (historical),
                              plus the security model, testing notes and example library
tv-app/                       The Android application (single Gradle module, :app)
  app/src/main/java/…/server      The local HTTP server and the dashboard's routes
  app/src/main/assets/            The dashboard itself: HTML, CSS and hand-written JavaScript
  app/src/main/java/…/playback     The player, the quality policy and playback authorization
  app/src/main/java/…/data/catalog The catalog model, its store and its sync service
  app/src/test/                    JVM tests (run in CI)
  app/src/androidTest/             Instrumentation tests (need a device; not run in CI)
  scripts/                         Device scripts: the E2E harness and the safe verifier
.github/workflows/ci.yml      The checks that run on every push
```

## Day-to-day configuration

- **Where the dashboard listens.** The app serves it on port 8080 and shows the address on screen.
  Nothing needs to be configured for this; the address follows the TV's network address.
- **Where the data lives.** On the TV, in the app's own database and preferences. Clearing the app's data
  from Android's settings erases the library and the parent credentials in the same way a reset does.
- **Time limits.** The dashboard can set a daily allowance, a bedtime, a manual lock and bonus time. The
  TV shows the remaining time; when it runs out the child sees the limit rather than the library.
- **Parent access on the TV.** The TV's own settings screen has a Parent access panel, which is where the
  PIN is created and the Recovery Code shown.

## Contributor onboarding

1. Read the [README](../README.md) for the product and
   [ARCHITECTURE_AND_SECURITY.md](ARCHITECTURE_AND_SECURITY.md) for the invariants, before changing
   anything that touches playback, the catalog or a credential.
2. Check the baseline: `git log -1`, `git status`, and that the stable tag has not moved.
3. Build and test with the Gradle wrapper (see the README).
4. Keep the two remotes straight: `fork` is where this work goes, `origin` is the read-only upstream.
   The local branch may track the upstream, so push explicitly: `git push fork main`.
5. Never point a destructive device tier at a television someone is using — see
   [TESTING_AND_DEVICE_VERIFICATION.md](TESTING_AND_DEVICE_VERIFICATION.md).
