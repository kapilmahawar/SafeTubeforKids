# Architecture and security

What SafeTube is made of, which invariants must keep holding, and why some decisions look the way they
do. Read this before changing anything that touches playback, the catalog, or a credential.

## The shape of the app

One Gradle module (`:app`) containing everything: the TV interface, the local HTTP server that serves the
parent dashboard, the catalog, the player, and the authentication for parent access.

```text
Android TV UI (Compose)          Parent dashboard (HTML/CSS/JS served by the app)
        |                                        |
        |  D-pad, focus, navigation              |  HTTP + a session token
        v                                        v
 HomeScreen / CatalogContainer / TvPlayerScreen      app/src/main/assets (app.js, catalog-editor.js, …)
        |                                        |
        v                                        v
                     PlaybackController                  Ktor server on the TV, port 8080
                            |                                    |
                            v                                    v
                   PlaybackAuthorization  <---- the single gate ---+
                            |                                    |
                            v                                    v
                     VideoResolver / Media3              CacheDatabase (Room) + preferences
                            |
                            v
                     YouTube (via NewPipeExtractor)
```

Fixed facts worth knowing: `applicationId tv.safetubeforkids.app`, versionCode 16 / versionName 0.10.0,
minSdk 24, targetSdk 34, compileSdk 36, Kotlin + Compose, Media3 for playback, Room for storage, Ktor
(Netty) for the local server. The dashboard is hand-written JavaScript with no build step, which is why
there are parse checks and model tests over it in CI.

## The invariant that matters most

**The catalog is not permission to play.** Two different stores answer two different questions:

| Store | Answers | Used by |
|---|---|---|
| Catalog (`catalog_nodes`) | What may the child *see*? | The library screens |
| Approved sources (`channels`) and the approved video cache (`videos`) | What may *play*? | `PlaybackAuthorization` |

`PlaybackAuthorization.authorize` is the only path into the player. It reads the `videos` and `channels`
tables and nothing else, and it refuses in two ways: an item that is not in the approved cache is
`This video is not approved`, and an item whose source has been withdrawn is `This source was removed`.
`approvedQueue` builds the playable queue from the same tables, so Next and Previous can never walk into
the catalog.

Consequences that are easy to break by accident:

- A catalog entry must never create an authorization record. The dashboard's link resolver is
  deliberately not the endpoint that approves a source.
- A source or shelf id must never be usable as a video id.
- A queue is a snapshot, not a permission slip. Every queue move re-reads the approved queue, so an item
  withdrawn while a child is watching simply is not in it any more.
- Every control that starts playback goes through the controller, which authorizes first. The player
  surface holds no permission of its own.

## Parent access

- The **Parent PIN** is created on the TV during first-run setup and entered on the TV's keypad. There is
  no default PIN.
- The dashboard's session comes from signing in with that PIN. Signing in produces a session token for
  that browser; changing the PIN or signing out everywhere invalidates sessions.
- The **Recovery Code** is shown once at setup and is the way back in with a forgotten PIN; it can also
  replace the PIN.
- Credential checks are rate-limited, and a failed verification is distinguishable from being configured
  at all: `/auth/state` says whether a PIN exists, which is not a secret, and never returns the code.
- **There is no HTTP route that wipes the TV.** The destructive reset lives on the TV itself, behind a
  typed confirmation phrase. That is a requirement rather than an omission: an unauthenticated request
  must not be able to erase a child's library.

## The dashboard and its server

The TV serves the dashboard. Routes fall into three groups:

- **Unauthenticated, deliberately:** `/` (the page), the static assets it loads, `/auth/state`, and
  `/status`. The first two have to be reachable before anyone has signed in; the last two reveal whether
  the TV is configured and busy, which is not secret.
- **Session required:** the catalog (`GET`/`PUT /catalog`), the approved sources (`/playlists`,
  `/sources/export`, `/sources/import`), catalog import resolution, the app whitelist and kiosk toggle,
  time limits, stats, and the playback commands the dashboard can send.
- **Debug only, absent from a release build:** the debug receiver that the device tests use. It is not in
  the merged release manifest, and `BuildConfig.IS_DEBUG` is false there.

Two write-path rules are worth keeping:

- **The catalog is written as one document.** `PUT /catalog` carries the version the editor read; a stale
  version is a conflict rather than a silent overwrite, and a failed or abandoned edit leaves the catalog
  exactly as it was.
- **A source is added once.** A source may not be added twice; the answer is a conflict, not a duplicate.
  Adding a source is instantaneous — there is no YouTube call in that request — and resolution happens
  afterwards. The dashboard's reading of the answer therefore has to treat *created* as success, which is
  what W13.12 fixed.

## Playback

`PlaybackController` owns playback: it authorizes, resolves, prepares, counts watch time, and decides
quality. The UI holds no media state and cannot start a video on its own.

- **Quality.** Automatic starts from a measurement and a policy, steps down after a stall and back up
  once playback has been clean; a manual choice pins a rendition and is not overridden. The chip reports
  the request and the rendition actually being rendered when the two differ.
- **Audio and captions.** Both are menus built from what the resolver reported for that item. A caption
  choice pins the *matching* track rather than the first text track, survives a re-prepare, and falls back
  to off for an item without that language.
- **Speed** is a viewing choice held by the controller, applied to the player, and kept across a
  re-prepare.
- **Retry** re-enters the same path and now asks authorization again before resolving anything (W14.1);
  before that fix, a retry could resolve a video whose source had just been withdrawn.
- **Resume.** Positions are recorded per video while watching. A position below a small floor is not
  offered, a nearly finished item starts over, and opening an item starts playback and offers the choice
  of carrying on rather than blocking on an answer.

## The player's focus contract

Left and Right are three different things depending on what holds focus, and this is a deliberate design
rather than an accident of layout:

```text
fullscreen surface   LEFT / RIGHT = seek
settings row         LEFT / RIGHT = move between the setting chips
transport row        LEFT / RIGHT = move along the transport row
```

The transport row keeps its focus through a long investigation (W13.2, deferred then fixed): the row is
composed only while the overlay is visible, so the auto-hide used to remove the focused control from the
composition and hand focus back to the full-screen surface — which is exactly what "Left and Right leave
the row" looked like. The fix stands the auto-hide down while a transport control holds focus, and links
the five controls to each other with explicit neighbours so the geometric search can never hand the row
to the surface. A source-order check in CI now guards the three-way behaviour, because no unit test can
reach inside a composable's key handler.

## Data and its limits

- Everything a parent configures lives on the TV, in the app's own Room database and preferences.
- Video streams are fetched from YouTube through an embedded extractor, so YouTube sees what a player
  would ask for. Captions and artwork come from the same place.
- The code contains an optional relay client, with a relay address constant, for remote dashboard access.
  It is not needed for ordinary use and is off unless configured; it is mentioned here so nobody reads
  the local design as a promise that the app makes no outbound connection beyond YouTube.
- Clearing the app's data from Android's settings, or resetting SafeTube from its own settings, erases
  the library and the parent credentials.

## Decisions a future contributor must preserve

1. **One authorization gate.** No shortcut may play a YouTube id directly, and no UI control may bypass
   `PlaybackAuthorization`.
2. **No unauthenticated destructive route.** The wipe stays on the TV, behind a typed confirmation.
3. **The catalog is written as one versioned document,** and a source is approved separately from being
   shown.
4. **The player's key handling is contextual on purpose** — see the focus contract above.
5. **Instrumentation against a real device is destructive to that device's app data,** which is why the
   Gradle build refuses to run a connected or uninstall task unless the run opts in, and why device
   verification has a separate, read-only path.
6. **Report what was observed.** A blocked check is blocked; a CI result is not a device result.
