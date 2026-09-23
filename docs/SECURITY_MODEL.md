# Security model

How SafeTube for Kids actually enforces "only approved content", where the boundaries are, and what
must not be weakened. Everything below was checked against the code at
`3962d312c27edbcd0e075e9840ff88fe8464b118`.

> **The central rule:** `PlaybackAuthorization` is the authoritative playback security boundary.
> Nothing may reach the player without passing through it.

---

## 1. The gate, exactly

`playback/PlaybackAuthorization.kt` — 73 lines, **unchanged since the Phase 1 baseline (`b130c12`)**.
Verified with `git diff b130c12 HEAD -- .../PlaybackAuthorization.kt` → no changes.

```kotlin
object PlaybackAuthorization {
    suspend fun authorize(db: CacheDatabase, videoId: String): PlaybackApproval

    suspend fun approvedQueue(db: CacheDatabase, sourceId: String): List<VideoItem>
}
```

```text
a requested YouTube video id
        │
        ▼
videoDao().getByVideoId(videoId)              ── absent ──▶ Rejected("This video is not approved")
        │ present in the approved `videos` cache
        ▼
channelDao().getBySourceId(entity.playlistId) ── absent ──▶ Rejected("This source was removed")
        │ the item's parent source is still in the approved `channels` table
        ▼
Approved(video, sourceId)
```

Two tables, two questions: *is this exact video in the approved cache?* and *is the parent source it
came from still approved?* Both must hold. Removing a source revokes every video beneath it in one
step, which is the "a parent removed the playlist" case.

`approvedQueue(db, sourceId)` is the second half of the boundary: it returns the cached videos of an
approved source, and **an empty list for a source that is not approved**. That list is the only queue
the player may walk, so `next`, `previous` and end-of-video autoplay can never reach a video the
parent did not approve — and can never become a YouTube recommendation.

### The single caller

`PlaybackController.boot()` is the only place in the app that starts playback, and it calls
`authorize` **before resolving or preparing any media**:

```kotlin
when (val approval = PlaybackAuthorization.authorize(db, videoId)) {
    is PlaybackApproval.Rejected -> {
        errorMessage = "This video can't be played"   // nothing is resolved, nothing is prepared
        PlayEventRecorder.endEvent(0, 0)
        currentVideoId = ""; resolved = null
        return
    }
    is PlaybackApproval.Approved -> {
        sourceId = approval.sourceId
        queue = PlaybackAuthorization.approvedQueue(db, sourceId)
        prepare(videoId)                              // only now does VideoResolver run
    }
}
```

`safeTube` has no other caller of `VideoResolver.resolve(...)` on a playback path.

---

## 2. Catalog membership ≠ playback authorization

This is the property most likely to be broken by a well-meaning change, so it is stated plainly and
enforced by tests.

- The catalog says **what the parent configured**. It carries no approval flag, no permission column
  and no foreign key to `channels` or `videos`. Its tables are
  `categories(id, display_name, sort_order, enabled, created_at, updated_at)`,
  `content_items(id, category_id, type, display_name, sort_order, youtube_playlist_id,
  youtube_video_id, enabled, created_at, updated_at)`,
  `catalog_metadata(id, catalog_version, server_version, last_successful_sync_at, last_attempt_at)` —
  and that is the complete list.
- **A catalog row cannot make a video play.** Adding `youtubeVideoId = X` to a shelf renders a card;
  pressing it still goes through `PlaybackController.boot()` → `PlaybackAuthorization.authorize(db, X)`,
  which consults `videos` and `channels` — tables the catalog never touches.
- **Nothing in the UI pre-judges permission.** The home screen renders catalog cards whether or not
  they are approved, and does not badge, hide or disable them by approval state. This is deliberate:
  the UI is not a second gate, and a "playable" hint computed in the UI could drift from the real
  decision. The refusal happens in the player, where the authority lives.
- **The one thing the UI asks the approval model** is
  `CatalogRepository.firstApprovedVideoOf(playlistId)` — which video to *start* a playlist card on.
  That is a starting point, not a grant: the chosen id then passes `authorize` like any other. It is
  also the only way to start a playlist without inventing a second queue.
- **Continue Watching cannot become an approval bypass.** Its query joins
  `playback_positions × videos × channels` with `INNER JOIN`s, so a video whose approval or source was
  withdrawn disappears from that shelf even though its position row remains. Watch history is not
  permission, and the shelf is built so that it cannot act like it. Selection still goes through the
  gate regardless.

### What was verified, and how

| Claim | Evidence |
|---|---|
| A catalog entry alone does not play | **On the Mi Box:** a card for `UNAPPROVED_DEVICE_VIDEO` was focused and pressed. Log: `[WARN] Blocked playback of unapproved video: UNAPPROVED_DEVICE_VIDEO`, then `[WARN] Playback rejected: This video is not approved`; `/status` reported nothing playing; the screen showed `This video can't be played` with Retry/Back. |
| A catalog entry that *is* approved plays | **On the Mi Box:** a catalog card for `MR5XSOdjKMA` (an approved video) → `Playing MR5XSOdjKMA`, `/status playing=true`. |
| Approval removal revokes playback while the catalog still lists the video | `CatalogHomeFlowTest.aSyncedCatalogEntryDoesNotGrantPlaybackPermission` — synchronizes a catalog, confirms rejection, approves the video through the existing model, confirms approval, then deletes the source while the catalog row stays and confirms rejection again. |
| A catalog playlist cannot smuggle in an unapproved playlist | `CatalogHomeFlowTest.aCatalogPlaylistCardStillCannotPlayAnUnapprovedPlaylist` |
| The catalog schema carries no permission | `CatalogDatabaseTest.catalogTablesCarryNoApprovalFlags` |

---

## 3. UI restriction vs actual enforcement

These are different things and must not be conflated. The UI's restrictions are *product design*;
they are not what stops a determined child, and they are not what the security argument rests on.

| | UI restriction (product design) | Enforcement (security) |
|---|---|---|
| Purpose | Keep the child inside a simple, safe experience | Make unapproved playback impossible |
| Where | `ui/` — the Compose screens | `PlaybackAuthorization`, called from `PlaybackController` |
| Failure mode if removed | A cluttered or confusing child UI | **Any YouTube video becomes playable — the product's premise is gone** |
| Examples | no search box; no URL field; no channel page; no "related videos"; no recommendation row; no card for anything the parent did not configure; no admin controls in the child UI | a video plays only if it is in the approved `videos` cache and its source is in `channels`; the queue is exactly the approved cache; unapproved ids are refused before any media is resolved |
| Verified how | harness checks that a child-facing screen exposes no search/URL entry; UI on the TV read via the accessibility tree; the nav graph has five destinations and none of them is a browser | harness `unapproved-video-blocked` / `unapproved-video-not-playing`; Phase 4 device test above; unit tests |

**Do not rely on the UI layer for security.** A future "hidden" or "disabled-looking" card is a UI
decision; the gate is the gate.

---

## 4. Playback request validation

Every path into the player, and what guards it:

| Entry point | Guard |
|---|---|
| A card in the catalog | `PlaybackController.boot()` → `authorize` (and the catalog row may be unapproved — it will be refused) |
| A card in the Continue Watching shelf | Same path; and the shelf query already excludes withdrawn approvals |
| A remote/media-key press in the player | Keys drive `PlaybackController`, which only ever acts on the already-authorized queue |
| The dashboard's play controls (`POST /playback/*`) | Session-authenticated; they move the player within the authorized queue |
| `DEBUG_PLAY_VIDEO` (debug builds only) | Routes into the same `Routes.PLAYBACK` → `authorize`. It cannot play an unapproved video: the harness uses it precisely to prove that. |
| A deep link (`VIEW` intent) | The app declares **no** `VIEW`/`BROWSABLE` intent filter, so it cannot be launched with one. Harness: `no-view-deeplink-handler`, `deeplink-plays-nothing`. |
| A URL passed as an activity extra | Nothing reads it for playback. Harness: `extras-cannot-start-playback`. |
| Any future route | Must go through `Routes.PLAYBACK` → `PlaybackController` → `authorize`. Creating a player that skips that call would be the single most serious possible defect. |

---

## 5. Approved playlists and approved individual videos

Both are represented in the same approval model, and both end in the same gate:

- **Playlist source** (`channels.source_type = "yt_playlist"`, `source_id = PL…`) — resolved into
  `videos` rows whose `playlistId` is the playlist id. Playing any of them authorizes that video and
  queues the rest of that playlist, in the parent's order, from the cache.
- **Individual video source** (`yt_video`, `source_id = <videoId>`) — resolved into a single `videos`
  row, using its own id as both `videoId` and `playlistId` (`ContentSourceRepository.resolveVideo`).
  Its "queue" is therefore itself, which is why the catalog's `VIDEO` cards pass the video id as the
  playlist id when no source is known — the id is not used to *decide* anything; `authorize` derives
  the real source from the database.
- **Channel source** (`yt_channel`) — resolves the channel's videos into the cache; the same gate
  applies to each.

A catalog `PLAYLIST`/`VIDEO` card does **not** need the corresponding approval to *render*. It needs
it to *play*. That gap is intentional and is the whole point of the separation.

---

## 6. Other boundaries that exist

- **Parent authentication.** The dashboard and every mutating API require a session. A session comes
  from `POST /auth` with the TV's PIN; `PinManager` rate-limits attempts (5, then a lockout) and
  `SessionManager` issues random 32-byte tokens with a 90-day TTL, capped at 20 (the oldest is evicted
  so a full store can never lock a parent out). The PIN is generated **in memory at every app start**
  and is never persisted.
- **The catalog API uses that same scheme** — no second auth system exists, and `PUT`/`GET /catalog`
  reject an anonymous or expired caller (verified: 401 on the real TV, and 4 route tests).
- **Kiosk mode** (optional, needs device-owner) can lock the TV to a whitelist of apps
  (`KioskManager`, `SafeTubeAdmin`, `HomeWatcherService`). This is a *device-level* restriction,
  orthogonal to playback authorization.
- **Screen time** (`TimeLimitManager`) can block playback and lock the screen; the player watches it
  and pauses on `Blocked`.
- **The child-facing app has no way to approve content**: no URL entry, no search, and no source
  editing. The Settings and Connect Phone buttons are deliberately reachable (a PIN gate was built,
  verified and then removed — see `/HANDOVER.md` for why); neither screen can add an approved video.
  A curious child can reach Reset PIN / Clear Sessions / Clear Events there; that residual is accepted
  and documented upstream.
- **Release builds contain no debug surface**: the release manifest has no `DebugReceiver`, and
  `CatalogSyncDebug`, `OfflineSimulator` and `BandwidthOverride` are debug-gated or inert.

---

## 7. Known security limitations

Honest list. None of these is a bypass of `PlaybackAuthorization`; they are limitations of the wider
model, and they should be fixed deliberately rather than incidentally.

1. **The catalog is configured over HTTP with the parent's session.** Anyone who has the PIN (shown on
   the TV) can rewrite the parent's catalog. That is the intended authority model, but it means the
   PIN is a real credential for configuration, not just for viewing.
2. **Catalog items are not required to be approved.** A parent can configure a shelf of unapproved
   videos; the cards render and every press is refused. Safe, but it can look broken — see
   `PROJECT_CONTEXT.md` §12.4.
3. **The debug "server unavailable" switch exists in debug builds only** (`CatalogSyncDebug.init`
   returns early unless `BuildConfig.IS_DEBUG`). It affects the sync's HTTP call and nothing else; it
   cannot influence authorization, which never consults it.
4. **`videos` is a cache, not an audit trail.** A source that fails to resolve does not remove its
   previously cached videos; `PlaybackAuthorization` therefore keeps approving them. That is intended
   (a network failure must not revoke a child's content) but it means approval is effectively "last
   successful resolve", not "current YouTube state".
5. **`ContentSourceRepository` caps a source at 200 videos** (`MAX_VIDEOS_PER_SOURCE`), so a very large
   approved playlist is only partly cached, and only the cached part is playable.
6. **Screen-time enforcement is cooperative.** It is enforced by the app's own player and UI; a child
   who reaches another app is handled only by kiosk mode if a parent enabled it.
7. **The relay path is inherited and pointed at upstream infrastructure**; it is not usable in this
   fork, and should be treated as untrusted legacy code if it is ever revived.

---

## 8. Rules for anyone changing this code

1. Do not modify, weaken, wrap-and-bypass, or "simplify" `PlaybackAuthorization`. It has been
   byte-identical since Phase 1 and should stay that way.
2. Do not add approval, permission or `playable` columns to the catalog tables.
3. Do not add a second code path that reaches `VideoResolver.resolve(...)` or `Media3` for playback.
4. Do not let the UI decide whether something may play.
5. Do not make a sync failure delete or partially write the catalog; the last-known-good catalog is a
   safety property, not an optimisation.
6. Do not add search, URL entry, recommendations, "related videos" or channel browsing to the
   child-facing app — and do not weaken the D-pad-only child surface.
7. Do not add an unauthenticated mutation endpoint, and do not relax the session check on `/catalog`.
8. Do not put a PIN, token, cookie or signing credential into code, logs, documentation or a
   screenshot that is committed.
9. If you believe a change to the security model is genuinely required, **stop and document it** with
   the reasoning instead of making it quietly.
