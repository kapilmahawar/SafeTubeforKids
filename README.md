# SafeTube for Kids

> **A parent-curated YouTube library for Android TV: the parent chooses the videos, the child browses
> only what was chosen, and a video in the library is still not allowed to play until playback
> authorization approves it.**

SafeTube turns a television into a small, closed library. A parent picks the content from their own
phone; the child sees those videos and nothing else. There is no search box, no home feed, no "related
videos", and no route back into YouTube itself. SafeTube is not an official YouTube product and is not
affiliated with YouTube.

![The SafeTube library on Android TV](docs/screenshots/w10/tv-child-library.png)

## What it is, and who it is for

- **The child watches** a library an adult assembled by hand: categories, the collections inside them,
  the videos inside those, and a Continue Watching row built from what they have actually watched. That
  is the whole interface.
- **The parent configures it** from any phone, tablet or computer on the same network, by opening the
  address the TV displays. The TV serves that page itself, so there is no account to create and no app to
  install on the phone.
- **Everything stays local.** The library is a database on the TV and the dashboard is served by the TV.
  Video streams come from YouTube; no Google account is used.

**How this differs from opening the YouTube app:** YouTube is built to keep showing you something new.
SafeTube is built to show a child the same small set of videos an adult chose, and to refuse everything
else — including a video that is *visible in the library* but whose source is no longer approved.

## Project philosophy

These are the rules the code is written to keep. They are worth reading before changing anything.

**1. Curated discovery, not search.** A child browses only what a parent put there. No unrestricted
search, no recommendations, no path from the child's screen into YouTube's own surfaces.

**2. The parent is the only source of content decisions.** Adding a source, building the catalog,
reordering it, hiding an item: all of it happens through the parent workflow, behind parent
authentication.

**3. Defense in depth — the catalog is not permission.** This is the invariant the rest of the design
serves. Being in the library means the child can *see* an item. Playing it requires a separate check
against the approved sources and the approved video cache, and that check is the only way into the
player:

```text
library (what the child sees)   ≠   approved sources + cached videos (what may play)
```

Putting a video in the library does not grant permission to play it, and nothing in the library can
change what is authorised. An item whose source is not approved still appears in the library — the
dashboard marks it **Can't play yet** — and pressing it on the TV plays nothing. The reverse holds too:
approving a source adds nothing to the child's library, because the parent still decides what the child
sees. Next, Previous and end-of-video autoplay walk the approved queue, never the catalog.

**4. TV-first, remote-first.** Every control is reachable with a D-pad and focus is always visible.
Nothing depends on touch, hover or a pointer. Where one key means different things in different places
(see the remote guide), that is deliberate, documented, and not to be flattened.

**5. Predictable behaviour.** Continue Watching, resume, navigation, retry and the player's controls
behave the same way every time; the same key does the same thing in the same place, and an error says
what happened instead of failing silently.

**6. Minimal complexity.** One Gradle module, one local database, a dashboard written by hand with no
build step, and no dependency that is not earning its place. Features that would make the system harder
for a parent to understand are deliberately absent.

**7. Privacy and local ownership — claimed only as far as the code supports it.** The library, the
approved sources and the parent credentials live on the TV, and the parent page is served by the TV over
the local network. Video streams and artwork are fetched from YouTube, so YouTube sees what a normal
player would ask for. The code also contains an optional relay client, with a relay address constant, for
remote dashboard access: it is not needed for ordinary use and is off unless configured. None of this
should be read as a claim that the app is offline, untraceable, or free of third-party traffic.

**8. Honest verification.** Automated results and real-device observations are kept apart, and a test
that could not be run is reported as blocked rather than quietly counted as a pass. The status table
below is written the same way.

**9. It curates the app, not the operating system.** SafeTube controls what happens *inside* SafeTube.
It also offers an in-app app-whitelist/kiosk screen, but that is a screen rather than an OS lockdown, and
it is not a substitute for the television's own parental controls.

## Screenshots

Captured from the app running on a Mi Box 4 (Android 12, 1920×1080) during the W10 parent-access
milestone. They are the images already published in this repository.

| | |
|---|---|
| ![The child's library on the TV](docs/screenshots/w10/tv-child-library.png) | ![A collection opened, showing its videos](docs/screenshots/w10/tv-collection-videos.png) |
| **The library on the TV.** One row per category the parent created, in the parent's order, using the parent's names. A card is a collection or a single video; nothing is recommended or sorted. | **A collection opened.** The videos inside it, in the parent's order, and only videos SafeTube is allowed to play. |
| ![First-run setup: creating the Parent PIN on the TV](docs/screenshots/w10/tv-first-run-create-pin.png) | ![Parent access on the TV](docs/screenshots/w10/tv-settings-parent-access.png) |
| **First-run setup.** Welcome, connect a phone, create the Parent PIN on the TV's own keypad, save the Recovery Code. | **Parent access on the TV.** Where the PIN and the Recovery Code are managed, and a last-resort reset lives. |
| ![The parent dashboard in a browser](docs/screenshots/w10/dashboard-signed-in.png) | ![The parent access panel](docs/screenshots/w10/dashboard-parent-access.png) |
| **The parent dashboard.** Served by the TV, opened on a phone on the same network. | **Parent access in the dashboard.** Signing in, and the way back in with a Recovery Code. |

Two honest notes about these images. The **player's own screen is not among them**: capturing it needs a
running player on a device whose contents may be published, and this phase deliberately did not stage a
family television for screenshots. A genuine player screenshot is therefore still outstanding, and the
project's device verification has a read-only script ready to make that capture possible. Second, the
setup screens show the TV's own local-network address, which is how a parent finds the dashboard: a
private-range address rather than a secret, and already part of this repository.

## Parent quick start

**You need:** an Android TV box (verified on a Mi Box 4 running Android 12), a phone or computer on the
same network, and the debug APK — see [Install and build](#install-and-build).

1. **TV:** install and open the app. A fresh install opens on setup.
2. **TV:** press Continue and note the address the next screen shows.
3. **TV:** create the **Parent PIN** — six digits, on the TV's own keypad. There is no default PIN and no
   factory password.
4. **TV:** write down the **Recovery Code** it displays. It is the way back in if the PIN is forgotten.
5. **Phone:** open the address the TV showed, in a browser, on the same network, and sign in with the
   Parent PIN.
6. **Dashboard:** approve sources. A source is a YouTube channel, playlist or single video; nothing from
   it can play until it is approved.
7. **Dashboard:** build the library — categories, collections inside them, and videos. A pasted YouTube
   link is previewed before anything is saved, and the whole library can be exported to a file and
   imported again.
8. **TV:** the child's screen now shows the library. Continue Watching fills in as videos are watched.

If the dashboard will not load, check that the app is running on the TV and that both devices are on the
same network. If a video stops playing, its source has most likely been withdrawn — approve it again, or
remove the item. Full workflows, including what each destructive action costs, are in the
**[project guide](docs/PROJECT_GUIDE.md)**.

## TV remote controls

| Where | Key | What it does |
|---|---|---|
| Anywhere | D-pad | Move focus; focus is always visible |
| Anywhere | Center/OK | Activate the focused thing |
| Library | Back | Leave a collection for the row it came from |
| Player | Down (or Menu/Info) | Reveal the controls; a further Down reaches the settings row, then the transport row |
| Player | Back | Close an open menu first, then leave the player |

**Left and Right mean three different things, on purpose.** They depend on what currently holds focus:

```text
fullscreen surface   LEFT / RIGHT = seek backward / forward (10 seconds, faster if held)
settings row         LEFT / RIGHT = move between the settings chips
transport row        LEFT / RIGHT = move along the transport row
```

The transport row is `Previous`, `Rewind 10 seconds`, `Play/Pause`, `Forward 10 seconds`, `Next`, and the
focused control is the one drawn with a ring, which is how a child knows where the remote is. This
behaviour was the subject of a long investigation and a fix; it is verified on the device and should not
be "simplified" without repeating that work.

In the settings row the chips report what is in use — `Subtitles: Off`, `Quality: Auto (360p)`,
`Speed: 1x` — and OK opens the matching menu. Menus close with Back without leaving the player.

## Install and build

Verified with JDK 17 and Android SDK build-tools 36.0.0. The build reads `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`); nothing else needs configuring.

```bash
cd tv-app
./gradlew assembleDebug                                   # debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk  # update in place, keeping app data
```

- Output: `tv-app/app/build/outputs/apk/debug/app-debug.apk`.
- `adb install -r` updates in place and **keeps the app's data**, so the library and the parent
  credentials survive it. It only works when the new APK carries the same signing key as the installed
  one, which is what a debug build provides.
- **A release APK is a different matter.** `assembleRelease` needs a keystore that is deliberately not in
  this repository: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
  `RELEASE_KEY_PASSWORD` come from `tv-app/local.properties` or the environment, and without them the
  build fails at its signing check. There is no published release build and no release channel.
- Unit tests, both variants — the release variant is not a formality, it has caught timing races that
  pass in debug:

```bash
cd tv-app
./gradlew testDebugUnitTest
./gradlew testReleaseUnitTest
```

**Device testing limits.** The app has been verified on a **Mi Box 4 running Android 12 (API 31)** at
1080p. Other Android TV hardware, other Android versions and other resolutions have not been tested, and
nothing here should be read as a claim that they work.

## Developer and test workflow

1. **Check the baseline**: `git log -1`, `git status`, and that `W13-STABLE-2026-10-01` has not moved.
2. **Make the smallest change** that fixes the problem, and add the test that would have caught it.
3. **Run the tests you affected**, then both unit variants — and build the debug APK if you touched
   anything the app ships.
4. **CI** (`.github/workflows/ci.yml`) runs on every push: `assembleDebug testDebugUnitTest
   testReleaseUnitTest`, the destructive-instrumentation safety check, the dashboard JavaScript parse
   check, the dashboard model and guard tests, and the player key-contract check. It has no device
   attached, so it never proves device behaviour.
5. **Remotes:** `fork` is the writable remote, `origin` is the read-only upstream, and the local branch
   may track the upstream — so push explicitly with `git push fork main`. Never force-push, reset,
   rebase, or move a stable tag.
6. **Never point a destructive device tier at a television somebody is using.** See
   [TESTING_AND_DEVICE_VERIFICATION.md](docs/TESTING_AND_DEVICE_VERIFICATION.md).

Two device scripts define what "safe" means:

- `tv-app/scripts/tv-e2e.ps1` — the established end-to-end harness. Powerful, and **destructive to
  state**: its `example` and `full` tiers load a fixture library over the real catalog, and its playback
  entry point uses a debug broadcast. Only for a device whose catalog and data may be replaced.
- `tv-app/scripts/tv-safe-player-verify.ps1` — a separate, read-only path to the real player: it launches
  the app, walks the library with remote keys, opens an existing approved item, and observes the player
  through accessibility dumps. No fixture, no debug endpoint, no catalog or credential writes. It is the
  helper for a television that must be left exactly as it was found.

## Feature and verification status

| Area | Status | Notes |
|---|---|---|
| Android TV navigation | Verified | D-pad navigation, focus indication and the transport row were observed on the Mi Box; the transport behaviour is additionally guarded by a source check in CI |
| Parent catalog management | Implemented; verified by tests and by device use | Categories, collections, videos, ordering, hiding, import/export; the dashboard is hand-written JavaScript with a model and guard test suite |
| Playback authorization | Implemented; verified by tests and by device observation | The single gate before playback; unit tests cover withdrawn sources, stale caches and unapproved items, and a refusal was observed on the device |
| Player controls | Implemented; partly verified on device | Transport, seek, resume, speed, quality, captions, audio and screen fit exist and are unit-tested; the transport row and playback itself were observed on the device, while several menu paths are still to be verified there |
| Parent dashboard | Implemented; verified by tests and by device use | Served by the TV: sign-in, Recovery Code, catalog editor, sources, apps and time limits |
| Parent access on the TV | Verified | PIN creation, the Recovery Code and the dashboard sign-in path were exercised on the device |
| App whitelist / kiosk screen | Implemented | Managed from the dashboard; an in-app screen, not an OS lockdown |
| Physical device testing | Partial | Verified on a Mi Box 4, Android 12 (API 31) only. Instrumentation tests exist but are not run in CI |

## Limitations and roadmap

**Known limitations**

- One device family has been verified: the Mi Box 4 on Android 12 (API 31). Nothing else has been.
- Every approved source in the test library is a progressive stream, so DASH adaptive switching has never
  been exercised on a device, and the automatic quality upgrade is unit-tested rather than demonstrated
  on a television.
- Caption rendering has not been pixel-verified, and the caption and audio menus have not yet been
  driven on the device.
- Network-failure and process-death paths are covered by unit and integration tests, not by device
  observation.
- The instrumentation tests need a device, so they are not part of CI — and running them against a real
  television erases that television's app data, which is why the Gradle build refuses a connected or
  uninstall task unless the run opts in.
- Errors can only be produced on a device by manipulating the network or the media, so the error and
  retry screen has no device verification yet.
- A release build needs a keystore this repository does not contain, so the project has no published
  release artifact.

**Planned, and not implemented** — none of this exists today: player verification on the device through
the safe script (the current work), and, if real use ever justifies it, small player conveniences such as
an "up next" cue. There is no subtitle styling, no progress scrubbing, no frame-rate matching, and no
sleep timer.

How the project reached this point — milestones, the defects found, and how they were caught — is in
[docs/DEVELOPMENT_HISTORY.md](docs/DEVELOPMENT_HISTORY.md). The engineering reports behind each claim
above are kept in [`docs/`](docs/).

## License and attribution

SafeTube for Kids is free software, licensed under the **GNU General Public License, version 3 or later**.
The full text is in [LICENSE](LICENSE), and GPL-3.0 is what governs redistribution: it requires keeping
the notices and publishing source for derived versions.

Copyright (C) 2026 Kapil Mahawar.

SafeTube for Kids began as a fork of [ParentApproved.tv](https://github.com/Prasanna79/parentapproved) by
Prasanna K. The approval model and the "approved content only" premise come from that project, and its
licence is retained; the catalog, the dashboard, the player, the security boundary and the test tooling
have since been rebuilt here. The upstream project is credited as the origin rather than presented as
current branding, and [LICENSE](LICENSE) remains the authoritative statement of attribution.
