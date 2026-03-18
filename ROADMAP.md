# kdbx-git Android App — Roadmap

## Overview

The app exposes a single KDBX database via a `ContentProvider` and keeps it continuously
synchronized with a remote **kdbx-git** server using that server's WebDAV endpoints.
No KeePass-level merge logic is implemented client-side; all merging is delegated to the
server.

---

## Server Recap (relevant endpoints)

| Endpoint | Purpose |
|---|---|
| `GET /dav/{client_id}/database.kdbx` | Download current merged KDBX for this client |
| `PUT /dav/{client_id}/database.kdbx` | Upload a modified KDBX; server merges it into `main` |
| `GET /sync/{client_id}/events` | SSE stream; fires `branch-updated` when `main` advances — **not used, see UnifiedPush below** |
| `POST /push/{client_id}/endpoint` | Register or update a UnifiedPush endpoint URL for this client |
| `DELETE /push/{client_id}/endpoint` | Unregister the push endpoint for this client |

Wire format: raw, encrypted KDBX 4.x bytes. Auth: HTTP Basic (username = client ID, password = configured password).

The server does **not** implement ETags or conditional request headers. `Last-Modified` is
always `now()`, so it cannot be used for change detection.

---

## Sync State Model

Local state is tracked with three pieces of persisted metadata:

```
last_synced_hash   SHA-256 of the KDBX bytes that were last confirmed on the server
local_dirty        boolean — true if local DB has been written since the last successful push
sync_status        enum { Idle, Pulling, Pushing, Error(msg) }
```

From these, the sync engine derives the scenario:

| local_dirty | remote changed? | Scenario |
|---|---|---|
| false | false | **In-sync** — nothing to do |
| false | true | **Remote ahead** — pull only |
| true | false | **Local ahead** — push only |
| true | true | **Diverged** — push then pull |

"Remote changed" is detected by comparing a SHA-256 of the freshly downloaded bytes against
`last_synced_hash`.

---

## Sync Scenarios

### 1. First launch / empty local state

1. `GET /dav/{id}/database.kdbx` → store as the local file.
2. Persist SHA-256 of those bytes as `last_synced_hash`.
3. Set `local_dirty = false`.

### 2. Remote ahead (local clean)

Triggered by: UnifiedPush notification (planned — see server push note below), periodic poll, or explicit user refresh.

1. `GET /dav/{id}/database.kdbx` → compare SHA-256 with `last_synced_hash`.
2. If different: overwrite local file, update `last_synced_hash`.
3. If same: no-op (server de-dups identical commits anyway).
4. Notify ContentProvider observers so open KeePass clients reload.

### 3. Local ahead (remote clean)

Triggered by: local file write detected by ContentProvider.

1. `PUT /dav/{id}/database.kdbx` with local bytes.
2. `GET /dav/{id}/database.kdbx` to confirm the round-trip.
3. Update `last_synced_hash` to hash of confirmed bytes.
4. Set `local_dirty = false`.

Step 2 is necessary because the server may silently no-op an unchanged PUT, and because we
want `last_synced_hash` to always reflect what the server actually holds.

### 4. Diverged (both sides changed)

Triggered by: sync attempt when `local_dirty = true` and remote hash differs.

The kdbx-git server performs a KeePass-level merge on every PUT before committing to `main`.
The client exploits this:

1. `PUT /dav/{id}/database.kdbx` with local bytes.
   - Server merges local changes with the state already on `main`.
2. `GET /dav/{id}/database.kdbx` to retrieve the server-merged result.
3. Overwrite local file with the merged result.
4. Update `last_synced_hash`.
5. Notify ContentProvider observers.

No client-side KDBX merge is needed. All conflict resolution is handled server-side by
`keepass-nd`'s entry-level union merge.

### 5. Network unavailable / offline operation

- Local reads from ContentProvider are always served from the on-disk file and never blocked.
- Writes set `local_dirty = true` and queue a sync for when connectivity returns.
- A `ConnectivityManager.NetworkCallback` watches for network availability and triggers a
  sync immediately on reconnection.
- A periodic WorkManager job (configurable interval, default 15 min) provides a backstop for
  environments where push notifications or connectivity callbacks are unreliable.

### 6. PUT succeeds but GET fails (partial sync)

- After a successful PUT, if the subsequent GET fails, `local_dirty` remains `true` and
  `last_synced_hash` is NOT updated.
- The next sync attempt retries the full PUT → GET cycle.
- This means a redundant PUT may be sent, which is safe: the server de-dups identical content
  and only commits if the database actually changed.

### 7. PUT fails (upload error)

- `local_dirty` stays `true`; `last_synced_hash` is unchanged.
- Retry with exponential back-off (1 s, 2 s, 4 s … cap 5 min).
- User-visible error notification after 3 consecutive failures.

### 8. Concurrent writes from multiple apps on the same device

- The ContentProvider uses a `ReadWriteLock` around file access.
- Writes to the ContentProvider are serialized; only one pending push at a time.
- If a second write arrives while a push is in flight, it sets `local_dirty = true` again
  and a new sync is scheduled after the in-flight one completes.

---

## DocumentsProvider Design

The app implements a `DocumentsProvider` (a subclass of `ContentProvider` that integrates
with Android's **Storage Access Framework**) rather than a plain `ContentProvider`.

### Why DocumentsProvider, not ContentProvider?

A plain `ContentProvider` is either fully private (`android:exported="false"`, no other app
can reach it at all) or fully public (`exported="true"`, any app can construct the URI and
read the database without user consent). Neither is right.

A `DocumentsProvider` must be exported — the system requires it — but **all access is
mediated by the system file picker** (`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`).
An app that wants the database URI must ask the user to choose it through the picker.
Without that explicit user gesture, no permission grant exists and any attempted access is
rejected by the framework. Apps cannot guess or construct a valid URI on their own.

This is exactly the desired security model: the database is invisible to every app until
the user consciously hands it out through the picker, just like any other document on the
device.

### Structure

The provider exposes one root and one document:

```
Root  "kdbx-git sync"
└── Document  "database.kdbx"   (MIME: application/octet-stream)
```

### Key operations

| Operation | Behaviour |
|---|---|
| `queryRoots()` | Return one root row describing the sync provider |
| `queryDocument(docId)` | Return metadata row: display name, size, last-modified |
| `queryChildDocuments(parentDocId)` | Return the single `database.kdbx` document row |
| `openDocument(docId, mode, signal)` | Open read or read/write `ParcelFileDescriptor` |

`openDocument` in write mode uses a staging-file approach: the caller writes to a temp
file; a `ProxyFileDescriptorCallback` intercepts the close, atomically replaces the live
file, then triggers sync.

### Notifications

After every successful pull, `getContext().getContentResolver().notifyChange(docUri, null)`
is called so that any app holding the document URI (e.g. KeePassDX) is notified and can
reload the file.

---

## Sync Log Design

### Data model

Each sync attempt — successful or not — appends one `SyncLogEntry` to a Room database.

```
SyncLogEntry {
    id          : Long (auto-generated primary key)
    timestamp   : Long (epoch ms, indexed for ordering)
    trigger     : Enum { MANUAL, WRITE, CONNECTIVITY, PERIODIC, PUSH }
    type        : Enum { PULL, PUSH, PUSH_PULL }   // what was attempted
    outcome     : Enum { SUCCESS, MERGED, NO_CHANGE, FAILURE }
    bytesDown   : Long   // bytes received in GET (0 if no pull)
    bytesUp     : Long   // bytes sent in PUT (0 if no push)
    durationMs  : Long   // wall time for the entire sync attempt
    errorMessage: String? // null on success; short message on failure
}
```

`trigger` values:

| Value | Meaning |
|---|---|
| `MANUAL` | User pressed "Sync now" |
| `WRITE` | Local database write detected by DocumentsProvider |
| `CONNECTIVITY` | Network became available after offline period |
| `PERIODIC` | WorkManager periodic job fired |
| `PUSH` | UnifiedPush notification received (future) |

```
```

`MERGED` means the server returned a database that differed from both the uploaded local
version and the previous `last_synced_hash` — i.e. the server performed a KeePass merge.
`NO_CHANGE` means neither side had changed (hash matched, `local_dirty` was false).

### Retention

The log is capped at **200 entries**. When a new entry would exceed this limit, the oldest
entry is deleted in the same Room transaction.

### UI

The main activity shows the log as a `RecyclerView` below the sync status header:

```
┌──────────────────────────────────────┐
│  kdbx-git sync          [Sync now ▶] │
│  Status: Idle  •  Last sync: 2 m ago │
├──────────────────────────────────────┤
│  17 Mar 14:32  Manual      PUSH_PULL  ✓ Merged   │
│  17 Mar 11:15  Periodic    PULL       ✓ OK       │
│  17 Mar 09:01  Write       PUSH       ✗ Network  │
│  …                                   │
└──────────────────────────────────────┘
```

Each row shows: timestamp, type, outcome badge, and — on failure — the error message
truncated to one line. The list is driven by a `Flow<List<SyncLogEntry>>` from Room,
collected in the ViewModel, so new entries appear automatically.

---

## Architecture

```
┌─────────────────────────┐
│  KeePass client app     │  e.g. KeePassDX
│  (KeePassDX, etc.)      │
└──────────┬──────────────┘
           │ user picks file via system picker
           │ (ACTION_OPEN_DOCUMENT)
           │ content://…/document/kdbx  (openDocument r/w)
┌──────────▼──────────────┐
│  KdbxDocumentsProvider  │  ← DocumentsProvider (SAF)
│  (read/write gated via  │    access only via system picker
│   ReadWriteLock)        │
└──────────┬──────────────┘
           │ write → enqueue expedited SyncWorker(WRITE)
┌──────────▼──────────────┐
│  WorkManager            │  ← All sync scheduling
│  PeriodicWorkRequest    │    NetworkType.CONNECTED constraint
│  OneTimeWorkRequest     │    Doze-safe, battery-efficient
│  (UnifiedPush, later)   │    no persistent process needed
└──────────┬──────────────┘
           │ SyncWorker.setForeground() → brief notification
           │ only while actively syncing; silent when idle
┌──────────▼──────────────┐
│  SyncRepository.sync()  │  ← Core sync logic (all 4 scenarios)
└──────────┬──────────────┘
           │ HTTP Basic + raw KDBX bytes
┌──────────▼──────────────┐
│  WebDavClient           │  ← OkHttp wrapper
│  GET / PUT              │
│  /dav/{id}/database.kdbx│
└─────────────────────────┘
```

### Key classes

| Class | Responsibility |
|---|---|
| `KdbxDocumentsProvider` | SAF DocumentsProvider; file access, observer notifications, picker integration |
| `SyncRepository` | Owns `last_synced_hash`, `local_dirty`, sync logic; writes `SyncLogEntry` on each attempt |
| `WebDavClient` | OkHttp-based GET/PUT with Basic Auth |
| `SyncWorker` | WorkManager `CoroutineWorker`; runs all sync triggers (WRITE, MANUAL, PERIODIC, CONNECTIVITY); calls `setForeground()` to show a brief notification only while actively syncing |
| `SyncLogEntry` / `SyncLogDao` | Room entity + DAO; capped at 200 entries |
| `MainViewModel` | Exposes `Flow<List<SyncLogEntry>>` and `syncStatus` to `MainActivity`; delegates manual sync to `SyncRepository` |
| `SettingsRepository` | Stores server URL, client ID, password (EncryptedSharedPreferences); username = client ID |

---

## Implementation Roadmap

### Phase 1 — Project skeleton & settings UI ✓

- [x] Create Android project (Kotlin, min SDK 26, target SDK 35)
- [x] Add dependencies: OkHttp, Kotlin Coroutines, WorkManager, AndroidX Security, Room
- [x] `SettingsScreen` (Compose) to configure server URL, client ID, password
- [x] `SettingsRepository` backed by `EncryptedSharedPreferences`
- [x] Basic app icon and manifest

### Phase 2 — WebDAV client ✓

- [x] `WebDavClient`: `suspend fun pull(): ByteArray` (GET)
- [x] `WebDavClient`: `suspend fun push(bytes: ByteArray)` (PUT)
- [x] HTTP Basic Auth interceptor
- [x] SHA-256 hash utility
- [x] Unit tests with MockWebServer

### Phase 3 — Local storage & sync state ✓

- [x] Store KDBX file in `filesDir` (not external storage)
- [x] `SyncRepository`: `last_synced_hash` and `local_dirty` in `SharedPreferences`
- [x] Implement the four sync scenarios as a single `suspend fun sync()` function
- [x] Atomic file replacement (write to temp, then `rename`)

### Phase 4 — DocumentsProvider ✓

- [x] `KdbxDocumentsProvider` skeleton, authority declared in manifest with `<provider android:exported="true" android:grantUriPermissions="true">`
- [x] `queryRoots()` — single root row
- [x] `queryDocument()` / `queryChildDocuments()` — single document row with live metadata
- [x] `openDocument("r")` — read-only `ParcelFileDescriptor`
- [x] `openDocument("rw")` — staging-file write with on-close listener, atomically commits and triggers sync
- [x] `ReadWriteLock` guarding file access
- [x] `notifyChange` after successful pulls

### Phase 5 — Sync service (revised, see Phase 6) ✓

> **Design change:** The original plan called for a persistent foreground service with a
> permanent notification and a live `ConnectivityManager.NetworkCallback`. This was dropped
> in favour of a WorkManager-only approach (see Phase 6): no process kept alive when idle,
> no permanent notification, Doze-safe. WorkManager's `NetworkType.CONNECTED` constraint
> replaces the connectivity callback; `SyncWorker.setForeground()` shows a brief
> notification only during active sync.

- [x] `SyncService` skeleton and manifest declarations
- [x] ~~Persistent foreground service with `ConnectivityManager.NetworkCallback`~~ — removed; see Phase 6

### Phase 6 — WorkManager sync (primary sync driver) ✓

- [x] `SyncWorker` (`CoroutineWorker`) calling `SyncRepository.sync(trigger)`
- [x] `setForeground(ForegroundInfo)` — show "Syncing…" notification only during active work
- [x] `PeriodicWorkRequest` (default 15 min, `NetworkType.CONNECTED`) — covers periodic and connectivity-restore triggers
- [x] Expedited `OneTimeWorkRequest` for `WRITE` and `MANUAL` triggers
- [x] Remove `SyncService`; update `KdbxDocumentsProvider`, `BootReceiver`, `MainActivity`, and `SettingsViewModel` to enqueue WorkManager instead
- [x] Cancel/reschedule `PeriodicWorkRequest` when settings change

### Phase 7 — Main UI, manual sync & sync log ✓

- [x] `MainActivity` with sync status header (current state + last sync time)
- [x] "Sync now" button that calls `SyncRepository.sync()` and shows an inline progress indicator while running; disabled while a sync is already in flight
- [x] `SyncLogEntry` Room entity and DAO (see Sync Log design below)
- [x] `LazyColumn` (Compose) displaying the rolling sync log
- [x] `SyncRepository` writes a `SyncLogEntry` at the end of every sync attempt

### Phase 8 — Error handling ✓

- [x] User-visible error after 3 consecutive failures (notification + in-app banner)
- [x] Conflict-resolved notification ("Database merged with remote changes")

### Phase 9 — Security & hardening ✓

- [x] HTTPS support (custom trust anchors for self-signed certs)
- [x] Wipe local file on credential change / logout
- [x] Review URI permission grants: `android:grantUriPermissions="true"` and `android:permission="android.permission.MANAGE_DOCUMENTS"` on the provider ensure all URI grants are mediated by the system file picker

### Phase 10 — Testing & polish

- [ ] Integration test: MockWebServer simulating all four sync scenarios
- [ ] End-to-end test with a real kdbx-git server (Docker Compose in CI)
- [ ] KeePassDX compatibility verification (open via content URI, save back)
- [ ] Battery / wake-lock audit

### Phase 11 — UnifiedPush instant sync

See `INSTANT_SYNC.md` for the full design.

**Server changes (kdbx-git server repo):**
- [ ] Add `push_endpoints` table to server database (`client_id`, `endpoint`, `last_seen_at`)
- [ ] Implement `POST /push/{client_id}/endpoint` — register / replace endpoint, update `last_seen_at`
- [ ] Implement `DELETE /push/{client_id}/endpoint` — unregister endpoint (idempotent)
- [ ] After every successful `main` commit: deliver `{"event":"branch-updated"}` to all
      endpoints where `last_seen_at > now() - 14 days`, in a background task (fire-and-forget, 5 s timeout)
- [ ] Auto-delete endpoints that return 404/410 from the push provider
- [ ] Periodic cleanup job: hard-delete rows where `last_seen_at < now() - 14 days`

**Android client changes:**
- [ ] Add `com.github.UnifiedPush:android-connector` dependency
- [ ] Add `WebDavClient` methods: `registerPushEndpoint(url)` / `deletePushEndpoint()`
- [ ] Implement `PushReceiver : MessagingReceiver` — `onNewEndpoint`, `onMessage`,
      `onUnregistered`; declare in manifest with UP intent filter
- [ ] Implement `PushRegistrationWorker` — retryable `CoroutineWorker` that POSTs/DELETEs
      the endpoint to the server
- [ ] Call `UnifiedPush.registerApp()` after settings are saved and on app start
- [ ] Call `UnifiedPush.unregisterApp()` on credential wipe / logout
- [ ] `SettingsRepository`: persist push endpoint URL in `EncryptedSharedPreferences`
- [ ] `SettingsScreen`: add UnifiedPush status row (active distributor name, or
      "no distributor — using periodic sync" if none is installed)
- [ ] `SyncTrigger.PUSH` is already defined — wire it up in `SyncWorker.enqueueSyncNow`
      call from `PushReceiver.onMessage`
- [ ] Schedule a `PeriodicWorkRequest` (3-day interval, `CHARGING + CONNECTED` constraints)
      that re-POSTs the stored endpoint URL to bump `last_seen_at` on the server

---

## Resolved Design Decisions

**ContentProvider access control** — Solved by using `DocumentsProvider` (SAF). The
provider is exported (required), but Android's Storage Access Framework ensures no app can
read or write the database without an explicit user gesture through the system file picker.
There are no guessable URIs and no ambient permissions.

**Client-side KDBX merge** — Not planned. All merge logic lives on the server
(`keepass-nd` entry-level union merge). Keeping merge complexity out of the client is a
deliberate long-term choice, not just a deferral.

**Key-file support** — Not supported. The KDBX master password is sufficient; the server
config's `keyfile` option is for server operators who want extra protection on the git
store, not something that changes per-sync. There is nothing to sync.

**No persistent foreground service** — A permanent foreground service (always-alive process,
permanent notification) was initially planned to own a `ConnectivityManager.NetworkCallback`
and a coroutine scope. This was dropped: a registered callback has negligible battery cost
when idle, but a permanent notification is poor UX and keeping the process alive is
unnecessary. WorkManager's `NetworkType.CONNECTED` constraint handles connectivity-triggered
sync; `SyncWorker.setForeground()` shows a brief notification only while actively syncing.
The result is zero idle overhead and no permanent notification.

**Multiple databases** — Out of scope. The kdbx-git server itself supports only one
database per deployment, so there is nothing to map to on the client side.

---

## Server Push

The kdbx-git server exposes an SSE endpoint (`GET /sync/{id}/events`) that fires a
`branch-updated` event whenever `main` advances. Using SSE for reactive sync would require
keeping a persistent HTTP connection open at all times, which drains battery unacceptably
on mobile.

The chosen alternative is **UnifiedPush** — see `INSTANT_SYNC.md` for the full design.
The server will gain two new endpoints (`POST/DELETE /push/{id}/endpoint`) for push
endpoint registration, and will deliver a lightweight `{"event":"branch-updated"}` payload
to all registered endpoints after every commit. The Android app will implement a
`MessagingReceiver` that responds by enqueuing an expedited `SyncWorker(PUSH)` — no
persistent connection needed.

Until UnifiedPush is implemented, sync is driven by:
1. **Write-triggered push** — expedited `SyncWorker` enqueued immediately after a local write
2. **Periodic WorkManager job** — `PeriodicWorkRequest` with `NetworkType.CONNECTED`; runs at most every 15 min and is automatically deferred and re-run when connectivity is restored (covers the offline → online case)
3. **Manual trigger** — expedited `SyncWorker` enqueued from the UI "Sync now" button
