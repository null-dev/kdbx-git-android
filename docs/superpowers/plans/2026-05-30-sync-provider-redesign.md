# Sync Provider Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor sync and DocumentsProvider code into clear file-store, sync-state, sync-engine, repository, and SAF-provider boundaries without changing user-visible behavior.

**Architecture:** `DatabaseFileStore` owns local KDBX file mechanics and locking. `SyncStateStore` owns persisted sync metadata. `SyncEngine` owns pull/push decisions against a `RemoteDatabaseClient`. `SyncRepository` coordinates app side effects, status, logging, notifications, and workers. `KdbxDocumentsProvider` becomes a thin SAF adapter.

**Tech Stack:** Kotlin, Android Storage Access Framework, WorkManager, Room, SharedPreferences, OkHttp WebDAV client, JUnit, kotlinx-coroutines-test.

---

## File Structure

- Create `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/RemoteDatabaseClient.kt`: small interface implemented by `WebDavClient` and faked in sync engine tests.
- Modify `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/WebDavClient.kt`: implement `RemoteDatabaseClient`.
- Create `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/SyncStateStore.kt`: sync metadata interface plus SharedPreferences-backed implementation.
- Create `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/DatabaseFileStore.kt`: live database file, metadata, staging, atomic replace, and locking.
- Create `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/SyncEngine.kt`: sync decision workflow and result model.
- Modify `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/SyncRepository.kt`: become coordinator around stores and engine.
- Modify `app/src/main/kotlin/ax/nd/kdbxgit/android/provider/KdbxDocumentsProvider.kt`: delegate file mechanics to `DatabaseFileStore`.
- Delete `app/src/main/kotlin/ax/nd/kdbxgit/android/provider/StagedDatabaseDiff.kt` if the helper is folded into `DatabaseFileStore`.
- Replace `app/src/test/kotlin/ax/nd/kdbxgit/android/provider/StagedDatabaseDiffTest.kt` with focused file-store tests, or delete it after equivalent coverage is added.
- Create `app/src/test/kotlin/ax/nd/kdbxgit/android/sync/DatabaseFileStoreTest.kt`.
- Create `app/src/test/kotlin/ax/nd/kdbxgit/android/sync/SyncEngineTest.kt`.

## Task 1: Introduce Remote Client And Sync State Boundaries

**Files:**
- Create: `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/RemoteDatabaseClient.kt`
- Create: `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/SyncStateStore.kt`
- Modify: `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/WebDavClient.kt`

- [ ] **Step 1: Add the remote client interface**

Create `RemoteDatabaseClient.kt`:

```kotlin
package ax.nd.kdbxgit.android.sync

interface RemoteDatabaseClient {
    suspend fun pull(): ByteArray
    suspend fun push(bytes: ByteArray)
}
```

- [ ] **Step 2: Make WebDavClient implement it**

Change the class declaration in `WebDavClient.kt`:

```kotlin
class WebDavClient(private val config: ServerConfig) : RemoteDatabaseClient {
```

Change the existing `pull` and `push` declarations:

```kotlin
override suspend fun pull(): ByteArray = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(dbUrl())
        .get()
        .build()
    http.newCall(request).execute().use { response ->
        response.requireSuccess()
        response.body.bytes()
    }
}

override suspend fun push(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
    val body = bytes.toRequestBody(KDBX_MEDIA_TYPE)
    val request = Request.Builder()
        .url(dbUrl())
        .put(body)
        .build()
    http.newCall(request).execute().use { response ->
        response.requireSuccess()
    }
}
```

- [ ] **Step 3: Add sync state store**

Create `SyncStateStore.kt`:

```kotlin
package ax.nd.kdbxgit.android.sync

import android.content.Context
import android.content.SharedPreferences

interface SyncStateStore {
    var lastSyncedHash: String?
    var localDirty: Boolean
    var consecutiveFailures: Int
    fun reset()
    fun markDirty() {
        localDirty = true
    }
}

class SharedPreferencesSyncStateStore(
    private val prefs: SharedPreferences,
) : SyncStateStore {
    override var lastSyncedHash: String?
        get() = prefs.getString(KEY_LAST_HASH, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_HASH) else putString(KEY_LAST_HASH, value)
            }.apply()
        }

    override var localDirty: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_DIRTY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCAL_DIRTY, value).apply()
        }

    override var consecutiveFailures: Int
        get() = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)
        set(value) {
            prefs.edit().putInt(KEY_CONSECUTIVE_FAILURES, value).apply()
        }

    override fun reset() {
        prefs.edit()
            .remove(KEY_LAST_HASH)
            .putBoolean(KEY_LOCAL_DIRTY, false)
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "kdbx_git_sync_state"
        private const val KEY_LOCAL_DIRTY = "local_dirty"
        private const val KEY_LAST_HASH = "last_synced_hash"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"

        fun from(context: Context): SharedPreferencesSyncStateStore =
            SharedPreferencesSyncStateStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
```

- [ ] **Step 4: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
jj status
jj describe -m "refactor: add sync client and state boundaries"
```

## Task 2: Add DatabaseFileStore

**Files:**
- Create: `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/DatabaseFileStore.kt`
- Create: `app/src/test/kotlin/ax/nd/kdbxgit/android/sync/DatabaseFileStoreTest.kt`

- [ ] **Step 1: Write file-store tests**

Create `DatabaseFileStoreTest.kt`:

```kotlin
package ax.nd.kdbxgit.android.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): DatabaseFileStore =
        DatabaseFileStore(filesDir = tmp.newFolder("files"), cacheDir = tmp.newFolder("cache"))

    @Test
    fun `metadata reports missing database`() {
        val metadata = store().metadata()

        assertFalse(metadata.exists)
        assertEquals(0L, metadata.size)
        assertEquals(0L, metadata.lastModified)
    }

    @Test
    fun `replace writes bytes and metadata reports file`() {
        val store = store()
        val bytes = byteArrayOf(1, 2, 3)

        store.replaceWith(bytes)

        assertArrayEquals(bytes, store.readBytesOrNull())
        assertTrue(store.metadata().exists)
        assertEquals(3L, store.metadata().size)
    }

    @Test
    fun `clear deletes database`() {
        val store = store()
        store.replaceWith(byteArrayOf(1))

        store.clear()

        assertNull(store.readBytesOrNull())
        assertFalse(store.metadata().exists)
    }

    @Test
    fun `unchanged staging commit reports false and keeps live bytes`() {
        val store = store()
        store.replaceWith(byteArrayOf(1, 2, 3))
        val staged = store.createStagingSnapshotForTest()

        val changed = store.commitStagingForTest(staged)

        assertFalse(changed)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.readBytesOrNull())
    }

    @Test
    fun `changed same-size staging commit replaces live bytes`() {
        val store = store()
        store.replaceWith(byteArrayOf(1, 2, 3))
        val staged = store.createStagingSnapshotForTest()
        staged.file.writeBytes(byteArrayOf(1, 2, 4))

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        assertArrayEquals(byteArrayOf(1, 2, 4), store.readBytesOrNull())
    }

    @Test
    fun `empty base can commit non-empty staged bytes`() {
        val store = store()
        val staged = store.createStagingSnapshotForTest()
        staged.file.writeBytes(byteArrayOf(9))

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        assertArrayEquals(byteArrayOf(9), store.readBytesOrNull())
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'ax.nd.kdbxgit.android.sync.DatabaseFileStoreTest'
```

Expected: compilation fails because `DatabaseFileStore` does not exist.

- [ ] **Step 3: Implement DatabaseFileStore**

Create `DatabaseFileStore.kt`:

```kotlin
package ax.nd.kdbxgit.android.sync

import android.os.Handler
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.locks.ReentrantReadWriteLock

class DatabaseFileStore(
    filesDir: File,
    private val cacheDir: File,
) {
    val dbFile: File = File(filesDir, DB_FILENAME)

    private val lock = ReentrantReadWriteLock()

    data class Metadata(
        val exists: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    data class StagedWrite(
        val file: File,
        val baseHash: String,
    )

    fun metadata(): Metadata {
        val exists = dbFile.exists()
        return Metadata(
            exists = exists,
            size = if (exists) dbFile.length() else 0L,
            lastModified = if (exists) dbFile.lastModified() else 0L,
        )
    }

    fun readBytesOrNull(): ByteArray? =
        if (dbFile.exists()) dbFile.readBytes() else null

    fun hashOrNull(): String? = readBytesOrNull()?.sha256Hex()

    fun clear() {
        lock.writeLock().lock()
        try {
            dbFile.delete()
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun replaceWith(bytes: ByteArray) {
        lock.writeLock().lock()
        try {
            writeAtomically(bytes)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun openForRead(): ParcelFileDescriptor {
        lock.readLock().lock()
        return try {
            if (!dbFile.exists()) throw FileNotFoundException("Database not yet synced")
            ParcelFileDescriptor.open(dbFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun openForWrite(
        handler: Handler,
        onCommitted: (changed: Boolean) -> Unit,
    ): ParcelFileDescriptor {
        val staged = createStagingSnapshot()
        return ParcelFileDescriptor.open(
            staged.file,
            ParcelFileDescriptor.MODE_READ_WRITE,
            handler,
        ) { error ->
            if (error == null) {
                onCommitted(commitStaging(staged))
            } else {
                staged.file.delete()
            }
        }
    }

    internal fun createStagingSnapshotForTest(): StagedWrite = createStagingSnapshot()

    internal fun commitStagingForTest(staged: StagedWrite): Boolean = commitStaging(staged)

    private fun createStagingSnapshot(): StagedWrite {
        val staging = File(cacheDir, "staged_${System.nanoTime()}.kdbx")
        lock.readLock().lock()
        return try {
            if (dbFile.exists()) dbFile.copyTo(staging, overwrite = true)
            else staging.createNewFile()
            StagedWrite(staging, staging.readBytes().sha256Hex())
        } finally {
            lock.readLock().unlock()
        }
    }

    private fun commitStaging(staged: StagedWrite): Boolean {
        lock.writeLock().lock()
        return try {
            val changed = staged.file.readBytes().sha256Hex() != staged.baseHash
            if (!changed) {
                staged.file.delete()
                false
            } else {
                commitStagedFile(staged.file)
                true
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun commitStagedFile(staging: File) {
        if (!staging.renameTo(dbFile)) {
            dbFile.writeBytes(staging.readBytes())
            staging.delete()
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        val tmp = File(dbFile.parentFile, "${dbFile.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dbFile)) {
            dbFile.writeBytes(bytes)
            tmp.delete()
        }
    }

    companion object {
        private const val DB_FILENAME = "database.kdbx"
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'ax.nd.kdbxgit.android.sync.DatabaseFileStoreTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
jj status
jj describe -m "refactor: extract database file store"
```

## Task 3: Add SyncEngine

**Files:**
- Create: `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/SyncEngine.kt`
- Create: `app/src/test/kotlin/ax/nd/kdbxgit/android/sync/SyncEngineTest.kt`

- [ ] **Step 1: Write sync engine tests**

Create `SyncEngineTest.kt`:

```kotlin
package ax.nd.kdbxgit.android.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileStore(): DatabaseFileStore =
        DatabaseFileStore(tmp.newFolder("files"), tmp.newFolder("cache"))

    @Test
    fun `clean no-change pull does not rewrite local bytes`() = runTest {
        val fileStore = fileStore()
        val bytes = byteArrayOf(1, 2, 3)
        fileStore.replaceWith(bytes)
        val state = FakeSyncStateStore(lastSyncedHash = bytes.sha256Hex())
        val remote = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(bytes)))

        val result = SyncEngine(fileStore, state).sync(remote)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.NO_CHANGE, result.outcome)
        assertFalse(result.documentChanged)
        assertArrayEquals(bytes, fileStore.readBytesOrNull())
    }

    @Test
    fun `clean remote-ahead pull replaces local bytes`() = runTest {
        val fileStore = fileStore()
        fileStore.replaceWith(byteArrayOf(1))
        val remoteBytes = byteArrayOf(2)
        val state = FakeSyncStateStore(lastSyncedHash = byteArrayOf(1).sha256Hex())
        val remote = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(remoteBytes)))

        val result = SyncEngine(fileStore, state).sync(remote)

        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertTrue(result.documentChanged)
        assertEquals(remoteBytes.sha256Hex(), state.lastSyncedHash)
        assertArrayEquals(remoteBytes, fileStore.readBytesOrNull())
    }

    @Test
    fun `dirty local-ahead push-pull clears dirty`() = runTest {
        val fileStore = fileStore()
        val localBytes = byteArrayOf(5)
        fileStore.replaceWith(localBytes)
        val state = FakeSyncStateStore(lastSyncedHash = byteArrayOf(4).sha256Hex(), localDirty = true)
        val remote = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(localBytes)))

        val result = SyncEngine(fileStore, state).sync(remote)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertEquals(listOf(localBytes.toList()), remote.pushes.map { it.toList() })
        assertFalse(state.localDirty)
        assertEquals(localBytes.sha256Hex(), state.lastSyncedHash)
    }

    @Test
    fun `dirty merged result replaces local bytes and reports merged`() = runTest {
        val fileStore = fileStore()
        val localBytes = byteArrayOf(5)
        val mergedBytes = byteArrayOf(6)
        fileStore.replaceWith(localBytes)
        val state = FakeSyncStateStore(lastSyncedHash = byteArrayOf(4).sha256Hex(), localDirty = true)
        val remote = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(mergedBytes)))

        val result = SyncEngine(fileStore, state).sync(remote)

        assertEquals(SyncOutcome.MERGED, result.outcome)
        assertTrue(result.documentChanged)
        assertArrayEquals(mergedBytes, fileStore.readBytesOrNull())
        assertFalse(state.localDirty)
    }

    @Test
    fun `stale dirty matching confirmed hash clears dirty without upload`() = runTest {
        val fileStore = fileStore()
        val bytes = byteArrayOf(7)
        fileStore.replaceWith(bytes)
        val state = FakeSyncStateStore(lastSyncedHash = bytes.sha256Hex(), localDirty = true)
        val remote = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(bytes)))

        val result = SyncEngine(fileStore, state).sync(remote)

        assertEquals(SyncOutcome.NO_CHANGE, result.outcome)
        assertTrue(remote.pushes.isEmpty())
        assertFalse(state.localDirty)
    }

    @Test
    fun `push failure preserves dirty and hash`() = runTest {
        val fileStore = fileStore()
        val localBytes = byteArrayOf(9)
        val oldHash = byteArrayOf(8).sha256Hex()
        fileStore.replaceWith(localBytes)
        val state = FakeSyncStateStore(lastSyncedHash = oldHash, localDirty = true)
        val remote = FakeRemoteDatabaseClient(pushError = IOException("offline"))

        val result = SyncEngine(fileStore, state).sync(remote)

        assertEquals(SyncOutcome.FAILURE, result.outcome)
        assertTrue(state.localDirty)
        assertEquals(oldHash, state.lastSyncedHash)
    }

    @Test
    fun `confirm pull failure preserves dirty and hash after successful push`() = runTest {
        val fileStore = fileStore()
        val localBytes = byteArrayOf(9)
        val oldHash = byteArrayOf(8).sha256Hex()
        fileStore.replaceWith(localBytes)
        val state = FakeSyncStateStore(lastSyncedHash = oldHash, localDirty = true)
        val remote = FakeRemoteDatabaseClient(pullError = IOException("pull failed"))

        val result = SyncEngine(fileStore, state).sync(remote)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.FAILURE, result.outcome)
        assertTrue(state.localDirty)
        assertEquals(oldHash, state.lastSyncedHash)
    }

    private class FakeSyncStateStore(
        override var lastSyncedHash: String? = null,
        override var localDirty: Boolean = false,
        override var consecutiveFailures: Int = 0,
    ) : SyncStateStore {
        override fun reset() {
            lastSyncedHash = null
            localDirty = false
            consecutiveFailures = 0
        }
    }

    private class FakeRemoteDatabaseClient(
        private val pulls: ArrayDeque<ByteArray> = ArrayDeque(),
        private val pullError: Exception? = null,
        private val pushError: Exception? = null,
    ) : RemoteDatabaseClient {
        val pushes = mutableListOf<ByteArray>()

        override suspend fun pull(): ByteArray {
            pullError?.let { throw it }
            return pulls.removeFirst()
        }

        override suspend fun push(bytes: ByteArray) {
            pushError?.let { throw it }
            pushes += bytes
        }
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'ax.nd.kdbxgit.android.sync.SyncEngineTest'
```

Expected: compilation fails because `SyncEngine` does not exist.

- [ ] **Step 3: Implement SyncEngine**

Create `SyncEngine.kt`:

```kotlin
package ax.nd.kdbxgit.android.sync

data class SyncEngineResult(
    val type: SyncType,
    val outcome: SyncOutcome,
    val bytesDown: Long,
    val bytesUp: Long,
    val documentChanged: Boolean,
    val errorMessage: String? = null,
)

enum class SyncEnginePhase {
    PULLING,
    PUSHING,
}

class SyncEngine(
    private val fileStore: DatabaseFileStore,
    private val stateStore: SyncStateStore,
) {
    suspend fun sync(
        client: RemoteDatabaseClient,
        onPhase: (SyncEnginePhase) -> Unit = {},
    ): SyncEngineResult {
        var bytesDown = 0L
        var bytesUp = 0L

        return try {
            val localBytes = fileStore.readBytesOrNull()
            val localHash = localBytes?.sha256Hex()
            val confirmedHash = stateStore.lastSyncedHash
            val hasLocalChange = stateStore.localDirty &&
                localBytes != null &&
                localHash != confirmedHash

            if (!hasLocalChange) {
                onPhase(SyncEnginePhase.PULLING)
                val remoteBytes = client.pull()
                bytesDown = remoteBytes.size.toLong()
                val remoteHash = remoteBytes.sha256Hex()
                val changed = remoteHash != stateStore.lastSyncedHash

                if (changed) {
                    fileStore.replaceWith(remoteBytes)
                    stateStore.lastSyncedHash = remoteHash
                }

                if (stateStore.localDirty && fileStore.hashOrNull() == remoteHash) {
                    stateStore.localDirty = false
                }

                SyncEngineResult(
                    type = SyncType.PULL,
                    outcome = if (changed) SyncOutcome.SUCCESS else SyncOutcome.NO_CHANGE,
                    bytesDown = if (changed) bytesDown else 0L,
                    bytesUp = 0L,
                    documentChanged = changed,
                )
            } else {
                onPhase(SyncEnginePhase.PUSHING)
                client.push(localBytes)
                bytesUp = localBytes.size.toLong()

                onPhase(SyncEnginePhase.PULLING)
                val remoteBytes = client.pull()
                bytesDown = remoteBytes.size.toLong()
                val remoteHash = remoteBytes.sha256Hex()
                val merged = remoteHash != localHash

                fileStore.replaceWith(remoteBytes)
                stateStore.lastSyncedHash = remoteHash
                stateStore.localDirty = false

                SyncEngineResult(
                    type = SyncType.PUSH_PULL,
                    outcome = if (merged) SyncOutcome.MERGED else SyncOutcome.SUCCESS,
                    bytesDown = bytesDown,
                    bytesUp = bytesUp,
                    documentChanged = true,
                )
            }
        } catch (e: Exception) {
            SyncEngineResult(
                type = if (bytesUp > 0L) SyncType.PUSH_PULL else SyncType.PULL,
                outcome = SyncOutcome.FAILURE,
                bytesDown = bytesDown,
                bytesUp = bytesUp,
                documentChanged = false,
                errorMessage = e.message ?: "Unknown error",
            )
        }
    }
}
```

- [ ] **Step 4: Run sync engine and file store tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'ax.nd.kdbxgit.android.sync.SyncEngineTest' --tests 'ax.nd.kdbxgit.android.sync.DatabaseFileStoreTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
jj status
jj describe -m "refactor: extract sync engine"
```

## Task 4: Refactor SyncRepository Into Coordinator

**Files:**
- Modify: `app/src/main/kotlin/ax/nd/kdbxgit/android/sync/SyncRepository.kt`

- [ ] **Step 1: Replace direct file and prefs ownership**

In `SyncRepository.kt`, replace the existing `dbFile`, `prefs`, `lastSyncedHash`, `localDirty`, and `consecutiveFailures` fields with:

```kotlin
private val fileStore = DatabaseFileStore(context.filesDir, context.cacheDir)
private val stateStore = SharedPreferencesSyncStateStore.from(context)
private val syncEngine = SyncEngine(fileStore, stateStore)

val databaseFileStore: DatabaseFileStore
    get() = fileStore

val dbFile: File
    get() = fileStore.dbFile

val lastSyncedHash: String?
    get() = stateStore.lastSyncedHash

val localDirty: Boolean
    get() = stateStore.localDirty
```

Keep `dbFile`, `lastSyncedHash`, and `localDirty` as read-only compatibility accessors during the refactor.

- [ ] **Step 2: Refactor clearLocalData and markDirty**

Replace those methods with:

```kotlin
fun clearLocalData() {
    fileStore.clear()
    stateStore.reset()
    _syncStatus.value = SyncStatus.Idle
    notifier.onSuccess()
}

fun markDirty() {
    stateStore.markDirty()
}
```

- [ ] **Step 3: Replace sync body**

Replace `sync(trigger)` with:

```kotlin
suspend fun sync(trigger: SyncTrigger) {
    val config = settingsRepository.serverConfig.value ?: return

    syncMutex.withLock {
        val startMs = System.currentTimeMillis()
        val client = WebDavClient(config)
        val result = syncEngine.sync(client) { phase ->
            _syncStatus.value = when (phase) {
                SyncEnginePhase.PULLING -> SyncStatus.Pulling
                SyncEnginePhase.PUSHING -> SyncStatus.Pushing
            }
        }

        log(
            trigger = trigger,
            type = result.type,
            outcome = result.outcome,
            bytesDown = result.bytesDown,
            bytesUp = result.bytesUp,
            startMs = startMs,
            errorMessage = result.errorMessage,
        )

        if (result.outcome == SyncOutcome.FAILURE) {
            val msg = result.errorMessage ?: "Unknown error"
            stateStore.consecutiveFailures = stateStore.consecutiveFailures + 1
            _syncStatus.value = SyncStatus.Error(msg)
            notifier.onFailure(stateStore.consecutiveFailures, msg)
        } else {
            stateStore.consecutiveFailures = 0
            notifier.onSuccess()
            _syncStatus.value = SyncStatus.Idle
            if (result.documentChanged) notifyFileChanged()
        }
    }
}
```

- [ ] **Step 4: Remove private atomic write helper and prefs constants**

Delete `writeAtomically(bytes)`, `PREFS_NAME`, `KEY_LOCAL_DIRTY`, `KEY_LAST_HASH`, and `KEY_CONSECUTIVE_FAILURES` from `SyncRepository.kt`. Keep `DOCUMENTS_AUTHORITY` and `DB_DOC_ID` unchanged so existing URI behavior remains stable.

- [ ] **Step 5: Compile and run sync tests**

Run:

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests 'ax.nd.kdbxgit.android.sync.*'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
jj status
jj describe -m "refactor: make sync repository a coordinator"
```

## Task 5: Refactor KdbxDocumentsProvider Into SAF Adapter

**Files:**
- Modify: `app/src/main/kotlin/ax/nd/kdbxgit/android/provider/KdbxDocumentsProvider.kt`
- Delete: `app/src/main/kotlin/ax/nd/kdbxgit/android/provider/StagedDatabaseDiff.kt`
- Delete or replace: `app/src/test/kotlin/ax/nd/kdbxgit/android/provider/StagedDatabaseDiffTest.kt`

- [ ] **Step 1: Remove provider-owned file locking and hashing imports**

Remove these imports from `KdbxDocumentsProvider.kt`:

```kotlin
import ax.nd.kdbxgit.android.sync.sha256Hex
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.locks.ReentrantReadWriteLock
```

Remove this field:

```kotlin
private val lock = ReentrantReadWriteLock()
```

- [ ] **Step 2: Delegate metadata row to DatabaseFileStore**

Replace `addFileRow` with:

```kotlin
private fun addFileRow(cursor: MatrixCursor) {
    val metadata = syncRepository.databaseFileStore.metadata()
    cursor.newRow().apply {
        add(Document.COLUMN_DOCUMENT_ID,   SyncRepository.DB_DOC_ID)
        add(Document.COLUMN_DISPLAY_NAME,  "database.kdbx")
        add(Document.COLUMN_MIME_TYPE,     KDBX_MIME_TYPE)
        add(Document.COLUMN_FLAGS,         Document.FLAG_SUPPORTS_WRITE)
        add(Document.COLUMN_SIZE,          metadata.size)
        add(Document.COLUMN_LAST_MODIFIED, metadata.lastModified)
    }
}
```

- [ ] **Step 3: Delegate openDocument**

Replace `openDocument` with:

```kotlin
override fun openDocument(
    documentId: String?,
    mode: String?,
    signal: CancellationSignal?,
): ParcelFileDescriptor {
    val parsedMode = ParcelFileDescriptor.parseMode(mode ?: "r")
    val fileStore = syncRepository.databaseFileStore

    if (parsedMode == ParcelFileDescriptor.MODE_READ_ONLY) {
        return fileStore.openForRead()
    }

    return fileStore.openForWrite(handler) { changed ->
        if (changed) {
            syncRepository.markDirty()
            context!!.contentResolver.notifyChange(docUri(), null)
            SyncWorker.enqueueSyncNow(context!!, SyncTrigger.WRITE)
        }
    }
}
```

- [ ] **Step 4: Delete provider staging helper**

Delete `commitStaging(staging, baseHash)` from `KdbxDocumentsProvider.kt`.

Delete `app/src/main/kotlin/ax/nd/kdbxgit/android/provider/StagedDatabaseDiff.kt`.

Delete `app/src/test/kotlin/ax/nd/kdbxgit/android/provider/StagedDatabaseDiffTest.kt` after confirming `DatabaseFileStoreTest` covers unchanged and changed staging bytes.

- [ ] **Step 5: Compile and run tests**

Run:

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
jj status
jj describe -m "refactor: simplify documents provider"
```

## Task 6: Final Verification And Cleanup

**Files:**
- Review all touched files.

- [ ] **Step 1: Search for obsolete responsibilities**

Run:

```bash
rg -n "stagedDatabaseChangedFromBase|PREFS_NAME|KEY_LOCAL_DIRTY|KEY_LAST_HASH|KEY_CONSECUTIVE_FAILURES|writeAtomically|ReentrantReadWriteLock" app/src/main/kotlin app/src/test/kotlin
```

Expected:

- No `stagedDatabaseChangedFromBase`.
- Preference constants only in `SharedPreferencesSyncStateStore`.
- `writeAtomically` only in `DatabaseFileStore`.
- `ReentrantReadWriteLock` only in `DatabaseFileStore`.

- [ ] **Step 2: Run full local verification**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect final diff**

Run:

```bash
jj diff
```

Expected:

- Provider is mostly cursor rows plus open delegation.
- Repository coordinates engine result side effects.
- Sync behavior still preserves dirty/hash state on failures.
- No manifest, WorkManager policy, WebDAV path, authority, document ID, or preference key changes.

- [ ] **Step 4: Commit final description if needed**

If the final change combines all implementation tasks in one `jj` change, describe it:

```bash
jj describe -m "refactor: redesign sync and documents provider"
```

If implementation was split into one `jj` change per task, keep the task descriptions from the previous commits.

## Self-Review Checklist

- Spec coverage: file storage, state storage, sync engine, repository coordination, provider simplification, failure behavior, and tests are each mapped to a task.
- Placeholder scan: no task relies on unspecified behavior or deferred implementation.
- Type consistency: `RemoteDatabaseClient`, `SyncStateStore`, `DatabaseFileStore`, `SyncEngine`, and `SyncEngineResult` names are consistent across tasks.
- Behavior preservation: authority, document ID, database filename, SharedPreferences name, preference keys, WorkManager enqueue behavior, and WebDAV semantics remain unchanged.
