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
| `GET /sync/{client_id}/events` | SSE stream; fires `branch-updated` when `main` advances — **not currently used, see server push below** |

Wire format: raw, encrypted KDBX 4.x bytes. Auth: HTTP Basic.

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
    type        : Enum { PULL, PUSH, PUSH_PULL }   // what was attempted
    outcome     : Enum { SUCCESS, MERGED, NO_CHANGE, FAILURE }
    bytesDown   : Long   // bytes received in GET (0 if no pull)
    bytesUp     : Long   // bytes sent in PUT (0 if no push)
    durationMs  : Long   // wall time for the entire sync attempt
    errorMessage: String? // null on success; short message on failure
}
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
│  17 Mar 14:32  PUSH_PULL  ✓ Merged   │
│  17 Mar 11:15  PULL       ✓ OK       │
│  17 Mar 09:01  PUSH       ✗ Network  │
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
           │ file write → dirty flag
┌──────────▼──────────────┐
│  SyncService            │  ← Foreground service
│  (coroutine-based)      │
│  - ConnectivityCallback │
│  - Periodic WorkManager │
│  - (UnifiedPush, later) │
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
| `SyncService` | Foreground service; owns connectivity callback and coroutine scope |
| `WebDavClient` | OkHttp-based GET/PUT with Basic Auth |
| `SyncWorker` | WorkManager `CoroutineWorker` for periodic background sync |
| `SyncLogEntry` / `SyncLogDao` | Room entity + DAO; capped at 200 entries |
| `MainViewModel` | Exposes `Flow<List<SyncLogEntry>>` and `syncStatus` to `MainActivity`; delegates manual sync to `SyncRepository` |
| `SettingsRepository` | Stores server URL, client ID, username, password (EncryptedSharedPreferences) |

---

## Implementation Roadmap

### Phase 1 — Project skeleton & settings UI

- [ ] Create Android project (Kotlin, min SDK 26, target SDK 35)
- [ ] Add dependencies: OkHttp, Kotlin Coroutines, WorkManager, AndroidX Security, Room
- [ ] `SettingsActivity` / `SettingsFragment` to configure server URL, client ID, credentials
- [ ] `SettingsRepository` backed by `EncryptedSharedPreferences`
- [ ] Basic app icon and manifest

### Phase 2 — WebDAV client

- [ ] `WebDavClient`: `suspend fun pull(): ByteArray` (GET)
- [ ] `WebDavClient`: `suspend fun push(bytes: ByteArray)` (PUT)
- [ ] HTTP Basic Auth interceptor
- [ ] SHA-256 hash utility
- [ ] Unit tests with MockWebServer

### Phase 3 — Local storage & sync state

- [ ] Store KDBX file in `filesDir` (not external storage)
- [ ] `SyncRepository`: `last_synced_hash` and `local_dirty` in `SharedPreferences`
- [ ] Implement the four sync scenarios as a single `suspend fun sync()` function
- [ ] Atomic file replacement (write to temp, then `rename`)

### Phase 4 — DocumentsProvider

- [ ] `KdbxDocumentsProvider` skeleton, authority declared in manifest with `<provider android:exported="true" android:grantUriPermissions="true">`
- [ ] `queryRoots()` — single root row
- [ ] `queryDocument()` / `queryChildDocuments()` — single document row with live metadata
- [ ] `openDocument("r")` — read-only `ParcelFileDescriptor`
- [ ] `openDocument("rw")` — staging-file write via `ProxyFileDescriptorCallback`, on-close triggers sync
- [ ] `ReadWriteLock` guarding file access
- [ ] `notifyChange` after successful pulls

### Phase 5 — Sync service

- [ ] `SyncService` as a started foreground service with persistent notification
- [ ] `ConnectivityManager.NetworkCallback` → trigger sync on network restore
- [ ] Wire `SyncService` start/stop to app lifecycle and settings changes
- [ ] _(UnifiedPush integration deferred — see server push note below)_

### Phase 6 — WorkManager background sync

- [ ] `SyncWorker` (CoroutineWorker) calling `SyncRepository.sync()`
- [ ] Periodic request with `PeriodicWorkRequest` (configurable, default 15 min)
- [ ] Constraint: `NetworkType.CONNECTED`
- [ ] Cancel/reschedule when settings change

### Phase 7 — Main UI, manual sync & sync log

- [ ] `MainActivity` with sync status header (current state + last sync time)
- [ ] "Sync now" button that calls `SyncRepository.sync()` and shows an inline progress indicator while running; disabled while a sync is already in flight
- [ ] `SyncLogEntry` Room entity and DAO (see Sync Log design below)
- [ ] `SyncLogAdapter` + `RecyclerView` displaying the rolling sync log
- [ ] `SyncRepository` writes a `SyncLogEntry` at the end of every sync attempt
- [ ] Persistent notification showing sync status (Idle / Syncing / Error) with a "Sync now" action

### Phase 8 — Error handling

- [ ] User-visible error after 3 consecutive failures (notification + in-app banner)
- [ ] Conflict-resolved notification ("Database merged with remote changes")

### Phase 9 — Security & hardening

- [ ] HTTPS support (custom trust anchors for self-signed certs)
- [ ] Wipe local file on credential change / logout
- [ ] Review URI permission grants: ensure `FLAG_GRANT_READ_URI_PERMISSION` / `FLAG_GRANT_WRITE_URI_PERMISSION` are scoped correctly

### Phase 10 — Testing & polish

- [ ] Integration test: MockWebServer simulating all four sync scenarios
- [ ] End-to-end test with a real kdbx-git server (Docker Compose in CI)
- [ ] KeePassDX compatibility verification (open via content URI, save back)
- [ ] Battery / wake-lock audit

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

**Multiple databases** — Out of scope. The kdbx-git server itself supports only one
database per deployment, so there is nothing to map to on the client side.

---

## Server Push (Future Work)

The kdbx-git server exposes an SSE endpoint (`GET /sync/{id}/events`) that fires a
`branch-updated` event whenever `main` advances. Using SSE for reactive sync would require
keeping a persistent HTTP connection open at all times, which drains battery unacceptably
on mobile.

The planned alternative is **UnifiedPush**: an open push-notification standard that
delivers lightweight wakeup messages through a separate push provider (e.g. Ntfy,
Gotify). This requires server-side support that does not exist yet. When the kdbx-git
server gains UnifiedPush support, the Android app will register a receiver that triggers a
sync on receipt of a push message — no persistent connection needed.

Until then, sync is driven by:
1. **Write-triggered push** — immediate sync after a local database write
2. **ConnectivityCallback** — sync on network reconnection after offline period
3. **Periodic WorkManager job** — backstop poll (default every 15 min)
