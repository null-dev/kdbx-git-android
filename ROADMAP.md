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
| `GET /sync/{client_id}/events` | SSE stream; fires `branch-updated` when `main` advances |

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

Triggered by: SSE `branch-updated` event, periodic poll, or explicit user refresh.

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
  environments where SSE or connectivity callbacks are unreliable.

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

## ContentProvider Design

### URI scheme

```
content://com.example.kdbxgit/database
```

A single resource. Clients open it as a file descriptor (like `FileProvider`).

### Operations

| Method | Behaviour |
|---|---|
| `openFile(uri, "r")` | Return a read-only `ParcelFileDescriptor` to the local KDBX file |
| `openFile(uri, "w")` | Return a write-redirect `ParcelFileDescriptor`; on close, set `local_dirty = true` and trigger sync |
| `query(uri, ...)` | Return a single-row cursor with `{_id, size, last_synced_ms, sync_status}` for status display |
| `getType(uri)` | `application/octet-stream` |

`openFile` in write mode uses a pipe/temp-file approach: the caller writes to a staging
file; when the descriptor is closed, the staging file atomically replaces the live file.

### ContentObserver notifications

After every successful pull, `getContext().getContentResolver().notifyChange(DATABASE_URI, null)`
is called so that any registered observers (e.g. an open KeePass app) can reload.

---

## Architecture

```
┌─────────────────────────┐
│  KeePass client app     │
│  (KeePassDX, etc.)      │
└──────────┬──────────────┘
           │ content://…/database  (openFile r/w)
┌──────────▼──────────────┐
│  KdbxContentProvider    │  ← Android ContentProvider
│  (read/write gated via  │
│   ReadWriteLock)        │
└──────────┬──────────────┘
           │ file write → dirty flag
┌──────────▼──────────────┐
│  SyncService            │  ← Foreground service / WorkManager worker
│  (coroutine-based)      │
│  - SSE listener         │
│  - ConnectivityCallback │
│  - Periodic WorkManager │
└──────────┬──────────────┘
           │ HTTP Basic + raw KDBX bytes
┌──────────▼──────────────┐
│  WebDavClient           │  ← OkHttp wrapper
│  GET / PUT              │
│  /dav/{id}/database.kdbx│
└──────────┬──────────────┘
           │ SSE
┌──────────▼──────────────┐
│  SseListener (OkHttp)   │  ← /sync/{id}/events
│  → triggers pull        │
└─────────────────────────┘
```

### Key classes

| Class | Responsibility |
|---|---|
| `KdbxContentProvider` | Android ContentProvider; file access, observer notifications |
| `SyncRepository` | Owns `last_synced_hash`, `local_dirty`, sync logic |
| `SyncService` | Foreground service; owns SSE connection and coroutine scope |
| `WebDavClient` | OkHttp-based GET/PUT with Basic Auth |
| `SseClient` | OkHttp streaming; parses `branch-updated` events |
| `SyncWorker` | WorkManager `CoroutineWorker` for periodic background sync |
| `SettingsRepository` | Stores server URL, client ID, username, password (EncryptedSharedPreferences) |

---

## Implementation Roadmap

### Phase 1 — Project skeleton & settings UI

- [ ] Create Android project (Kotlin, min SDK 26, target SDK 35)
- [ ] Add dependencies: OkHttp, Kotlin Coroutines, WorkManager, AndroidX Security
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

### Phase 4 — ContentProvider

- [ ] `KdbxContentProvider` skeleton, URI authority registered in manifest
- [ ] `openFile("r")` — read-only `ParcelFileDescriptor` via `openPipeHelper`
- [ ] `openFile("w")` — staging-file write, on-close triggers sync
- [ ] `query()` returning status row
- [ ] `ReadWriteLock` guarding file access
- [ ] `notifyChange` after successful pulls

### Phase 5 — Sync service & SSE

- [ ] `SyncService` as a started foreground service with persistent notification
- [ ] `SseClient` using OkHttp `EventSource` (or manual chunked-read); parse `branch-updated`
- [ ] Reconnect SSE with exponential back-off on disconnect
- [ ] `ConnectivityManager.NetworkCallback` → trigger sync on network restore
- [ ] Wire `SyncService` start/stop to app lifecycle and settings changes

### Phase 6 — WorkManager background sync

- [ ] `SyncWorker` (CoroutineWorker) calling `SyncRepository.sync()`
- [ ] Periodic request with `PeriodicWorkRequest` (configurable, default 15 min)
- [ ] Constraint: `NetworkType.CONNECTED`
- [ ] Cancel/reschedule when settings change

### Phase 7 — Error handling & UX

- [ ] Persistent notification showing sync status (In sync / Syncing / Error)
- [ ] User-visible error after 3 consecutive failures (notification + in-app banner)
- [ ] Manual "Sync now" button in settings
- [ ] Conflict-resolved notification ("Database merged with remote changes")

### Phase 8 — Security & hardening

- [ ] Enforce `android:exported="false"` on ContentProvider (or signature-level permission)
- [ ] Optional: allow-list of trusted client app signatures
- [ ] HTTPS support (custom trust anchors for self-signed certs)
- [ ] Wipe local file on credential change / logout

### Phase 9 — Testing & polish

- [ ] Integration test: MockWebServer simulating all four sync scenarios
- [ ] End-to-end test with a real kdbx-git server (Docker Compose in CI)
- [ ] KeePassDX compatibility verification (open via content URI, save back)
- [ ] Battery / wake-lock audit

---

## Open Questions

1. **Should the ContentProvider be exported?** Exporting to all apps is convenient but
   exposes the database to any installed app. Using a `signature`-level permission restricts
   access to apps signed with the same key (e.g. a companion KeePass fork).

2. **Client-side KDBX merge (future)?** If offline edits from two devices need to be merged
   before the user is back online, a local merge would be required. For now this is out of
   scope; the server handles merges on the next sync.

3. **Key-file support?** The kdbx-git server supports a `keyfile` for database encryption in
   addition to the master password. The app should eventually support delivering the key file
   alongside the KDBX file.

4. **Multiple databases?** The current design supports exactly one database per app install.
   Multiple databases would require separate app instances or a more complex URI scheme.
