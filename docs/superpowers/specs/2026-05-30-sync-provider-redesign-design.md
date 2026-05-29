# Sync And Documents Provider Redesign

## Goal

Rewrite the sync and Android DocumentsProvider code around clearer boundaries while preserving the app's external behavior:

- The app exposes one Storage Access Framework root named `kdbx-git sync`.
- The root contains one writable document named `database.kdbx`.
- KeePass clients can read and write the document through SAF grants.
- Local writes mark the database dirty and enqueue an expedited `SyncWorker(WRITE)`.
- Remote pulls replace the local file and notify document observers.
- Server-side kdbx-git merge remains the only merge mechanism.

The redesign prioritizes clear responsibilities over minimizing file count.

## Current Problem

`KdbxDocumentsProvider` currently handles SAF cursor rows, read/write descriptors, staging files, commit locking, dirty marking, document notifications, and sync scheduling.

`SyncRepository` currently handles live file storage, atomic writes, persisted sync metadata, sync decisions, WebDAV orchestration, log entries, notifications, status flow, and document URI notifications.

Those responsibilities are individually reasonable, but they are braided together. The rewrite should make the implicit concepts explicit so each class can be understood and tested independently.

## Architecture

### `DatabaseFileStore`

Owns local database file mechanics.

Responsibilities:

- Locate the live `database.kdbx` file under app internal storage.
- Report file metadata used by SAF rows: exists, size, and last modified.
- Open the live file for read while guarding against commit rename races.
- Create per-write staging files under `cacheDir`.
- Copy live bytes into staging while holding a read lock.
- Record a base hash for each writable open.
- On close, compare staged bytes with the base hash.
- Commit changed staging files over the live database under a write lock.
- Replace the live database with remote bytes during sync.
- Read live bytes and hash them for sync decisions.
- Clear the local database file when settings change.

This class owns the `ReentrantReadWriteLock`. Consumers should not coordinate file locking themselves.

### `SyncStateStore`

Owns persisted sync metadata in `SharedPreferences`.

Responsibilities:

- Read and write `lastSyncedHash`.
- Read, set, and clear `localDirty`.
- Read, reset, and increment consecutive failure count.
- Reset all sync state when settings change.

This keeps sync metadata persistence separate from network workflow and file mutation.

### `SyncEngine`

Owns the sync decision workflow.

Responsibilities:

- Pull remote bytes when local state is clean.
- Compare remote hash to `lastSyncedHash`.
- Replace local bytes only when the remote hash changed.
- Push local bytes when there is a real dirty local change.
- Pull after every successful push to confirm the server result.
- Store the confirmed server hash only after the confirm pull succeeds.
- Clear stale dirty state when the local bytes do not differ from the confirmed hash.
- Return a compact result describing sync type, outcome, byte counts, error, and whether local bytes changed.

`SyncEngine` should avoid Android UI or worker concerns. It can depend on `DatabaseFileStore`, `SyncStateStore`, and a WebDAV client interface or factory.

### `SyncRepository`

Remains the app-facing sync coordinator.

Responsibilities:

- Expose `syncStatus`.
- Serialize sync runs with a `Mutex`.
- Construct `WebDavClient` from current settings.
- Call `SyncEngine` inside the mutex.
- Insert capped sync log entries.
- Update `SyncNotifier` on success or failure.
- Notify document observers when sync replaced the local database.
- Provide `markDirty()` for the provider.
- Provide `clearLocalData()` for settings changes.

This class owns app side effects around the sync run, but not the detailed sync decision tree.

### `KdbxDocumentsProvider`

Becomes a SAF adapter.

Responsibilities:

- Return root and document cursor rows.
- Delegate file metadata to `DatabaseFileStore`.
- Delegate read and write opens to `DatabaseFileStore`.
- On changed write close, call `syncRepository.markDirty()`.
- Notify the document URI after local writes.
- Enqueue `SyncWorker.enqueueSyncNow(context, SyncTrigger.WRITE)` after local writes.

The provider should not know how hashes, atomic replacements, or sync scenarios work.

## SAF Read And Write Flow

### Read

1. `KdbxDocumentsProvider.openDocument(documentId, mode, signal)` parses the mode.
2. For read-only mode, the provider calls `DatabaseFileStore.openForRead()`.
3. `DatabaseFileStore` takes the read lock briefly, verifies the live file exists, opens a read-only `ParcelFileDescriptor`, and releases the lock.
4. If no database has been synced yet, `openForRead()` throws `FileNotFoundException("Database not yet synced")`.

### Write

1. For writable mode, the provider calls `DatabaseFileStore.openForWrite(handler, onCommitted)`.
2. `DatabaseFileStore` creates a unique staging file under `cacheDir`.
3. Under the read lock, it copies existing live bytes into staging or creates an empty staging file if the database does not exist.
4. It records the staging file's base SHA-256 hash.
5. It returns a read/write `ParcelFileDescriptor` backed by the staging file.
6. On clean close, it compares the staged file to the base hash.
7. If unchanged, it deletes staging and invokes `onCommitted(false)`.
8. If changed, it commits staging over the live database under the write lock and invokes `onCommitted(true)`.
9. On descriptor error, it deletes staging and does not invoke dirty/sync behavior.

When `onCommitted(true)` fires, the provider:

1. Calls `syncRepository.markDirty()`.
2. Calls `ContentResolver.notifyChange(documentUri, null)`.
3. Calls `SyncWorker.enqueueSyncNow(context, SyncTrigger.WRITE)`.

## Sync Flow

`SyncRepository.sync(trigger)` keeps the top-level orchestration:

1. If settings do not contain a server config, return without changing status.
2. Enter `syncMutex`.
3. Set status as `Pulling` or `Pushing` according to the phase reported by the engine.
4. Ask `SyncEngine` to run one sync cycle with the configured client.
5. Log the result.
6. On success, reset consecutive failures, clear error notification, and return to `Idle`.
7. On failure, increment consecutive failures, show failure notification, and set `SyncStatus.Error(message)`.
8. Notify the document URI if the engine reports that the live local file changed.

`SyncEngine` handles these scenarios:

### Clean Local State

1. Read `lastSyncedHash` and `localDirty`.
2. Read local bytes and local hash if the file exists.
3. If there is no real local change, pull remote bytes.
4. Hash the remote bytes.
5. If the remote hash differs from `lastSyncedHash`, replace the live database and store the new hash.
6. If the remote hash matches `lastSyncedHash`, do not rewrite the file.
7. If `localDirty` was stale and the current local hash equals the remote hash, clear dirty.

Outcome:

- `PULL` + `SUCCESS` when remote bytes replace local bytes.
- `PULL` + `NO_CHANGE` when no file change is needed.

### Dirty Local State

1. Read local bytes.
2. If local bytes are absent, treat this as no real local change and use the clean path.
3. If local hash equals `lastSyncedHash`, treat dirty as stale and use the clean path.
4. Push local bytes.
5. Pull remote bytes to confirm the server result.
6. Hash confirmed remote bytes.
7. Replace live local bytes with confirmed remote bytes.
8. Store confirmed hash as `lastSyncedHash`.
9. Clear `localDirty`.

Outcome:

- `PUSH_PULL` + `SUCCESS` when the confirmed remote hash equals the uploaded local hash.
- `PUSH_PULL` + `MERGED` when the confirmed remote hash differs from the uploaded local hash.

### Failure Cases

- Pull failure in clean path leaves `lastSyncedHash` and `localDirty` unchanged.
- Push failure leaves `localDirty = true` and leaves `lastSyncedHash` unchanged.
- Successful push followed by failed confirm pull leaves `localDirty = true` and leaves `lastSyncedHash` unchanged.
- The next sync retries the full safe cycle.

## Notifications

Document notifications remain intentionally narrow:

- Provider local write commit: notify immediately so clients see local metadata changes.
- Sync remote replacement: notify after replacing live bytes.
- No-change sync: do not notify.
- Failed sync: do not notify.

`SyncRepository` remains the one place that builds the document URI for sync notifications. `KdbxDocumentsProvider` can keep a local helper for provider-side notifications, or both can share a small `KdbxDocumentIds` object if that avoids duplicated constants.

## Testing

Most behavior should be covered outside Android provider tests.

### `DatabaseFileStoreTest`

Cover:

- Metadata for missing and existing database files.
- Read-only open throws `FileNotFoundException` when missing.
- Staging commit with unchanged bytes reports unchanged and does not replace the live file.
- Staging commit with changed same-size bytes replaces the live file.
- Staging commit from empty base to non-empty bytes replaces the live file.
- Remote replace writes bytes and updates metadata.
- Clear deletes the live file.

Existing `StagedDatabaseDiffTest` can be folded into this test suite if the helper becomes private to `DatabaseFileStore`.

### `SyncStateStoreTest`

Cover:

- Initial state has null hash, dirty false, and zero failures.
- Marking dirty persists.
- Storing hash persists.
- Reset clears hash, dirty, and failures.
- Incrementing and resetting failures persists.

If plain JVM SharedPreferences setup becomes noisy, keep this class thin and cover it indirectly through `SyncEngine` or Android tests.

### `SyncEngineTest`

Use a fake WebDAV client and temporary `DatabaseFileStore`.

Cover:

- Clean no-change pull logs `NO_CHANGE` and does not rewrite local bytes.
- Clean remote-ahead pull replaces local bytes and stores remote hash.
- Dirty local-ahead push-pull clears dirty and stores confirmed hash.
- Dirty merge result replaces local bytes with server result and reports `MERGED`.
- Dirty flag with unchanged local bytes clears stale dirty without uploading.
- Push failure preserves dirty and hash.
- Confirm pull failure after successful push preserves dirty and hash.

### Provider Testing

Keep `KdbxDocumentsProvider` light enough that it does not need broad provider tests. If provider behavior regresses later, add focused Android tests for cursor rows and open modes.

## Migration Notes

No user data migration is required. The live database filename, document authority, document ID, SharedPreferences name, and preference keys should remain unchanged unless a task explicitly includes migration code.

The first implementation pass should preserve existing runtime behavior and avoid changing WorkManager policies, WebDAV request semantics, settings behavior, or notification behavior.
