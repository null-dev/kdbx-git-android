package ax.nd.kdbxgit.android.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `clean no-change pull logs NO_CHANGE and does not rewrite local bytes`() = runTest {
        val localBytes = bytes("same")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = localBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(localBytes)))
        val originalModified = store.dbFile.lastModified()

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(
            SyncEngineResult(
                type = SyncType.PULL,
                outcome = SyncOutcome.NO_CHANGE,
                bytesDown = 0L,
                bytesUp = 0L,
                documentChanged = false,
            ),
            result,
        )
        assertArrayEquals(localBytes, store.readBytesOrNull())
        assertEquals(originalModified, store.dbFile.lastModified())
        assertEquals(localBytes.sha256Hex(), stateStore.lastSyncedHash)
        assertEquals(1, client.pullCount)
        assertEquals(0, client.pushes.size)
    }

    @Test
    fun `clean remote-ahead pull replaces local bytes and stores remote hash`() = runTest {
        val localBytes = bytes("local")
        val remoteBytes = bytes("remote")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = localBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(remoteBytes)))
        val phases = mutableListOf<SyncEnginePhase>()

        val result = SyncEngine(store, stateStore).sync(client, phases::add)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertEquals(remoteBytes.size.toLong(), result.bytesDown)
        assertEquals(0L, result.bytesUp)
        assertTrue(result.documentChanged)
        assertNull(result.errorMessage)
        assertEquals(listOf(SyncEnginePhase.PULLING), phases)
        assertArrayEquals(remoteBytes, store.readBytesOrNull())
        assertEquals(remoteBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `clean remote-ahead pull replace failure preserves local state`() = runTest {
        val localBytes = bytes("local")
        val remoteBytes = bytes("remote")
        val store = ThrowingReplaceFileStore(
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache"),
        )
        store.replaceWith(localBytes)
        store.failReplacements = true
        val stateStore = FakeSyncStateStore(lastSyncedHash = localBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(remoteBytes)))

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.FAILURE, result.outcome)
        assertEquals(remoteBytes.size.toLong(), result.bytesDown)
        assertEquals(0L, result.bytesUp)
        assertFalse(result.documentChanged)
        assertEquals("replace exploded", result.errorMessage)
        assertArrayEquals(localBytes, store.readBytesOrNull())
        assertEquals(localBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `clean remote-ahead pull preserves newer local bytes written during pull`() = runTest {
        val localBytes = bytes("local")
        val newerLocalBytes = bytes("newer local")
        val remoteBytes = bytes("remote")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = localBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(
            pulls = ArrayDeque(listOf(remoteBytes)),
            beforePullReturn = {
                store.replaceWith(newerLocalBytes)
            },
        )

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertEquals(remoteBytes.size.toLong(), result.bytesDown)
        assertEquals(0L, result.bytesUp)
        assertFalse(result.documentChanged)
        assertNull(result.errorMessage)
        assertArrayEquals(newerLocalBytes, store.readBytesOrNull())
        assertEquals(remoteBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `clean remote-ahead pull preserves local bytes written during transactional replace`() = runTest {
        val localBytes = bytes("local")
        val newerLocalBytes = bytes("newer local")
        val remoteBytes = bytes("remote")
        val store = MutatingConditionalReplaceFileStore(
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache"),
            mutateToBytes = newerLocalBytes,
        )
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = localBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(remoteBytes)))

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertEquals(remoteBytes.size.toLong(), result.bytesDown)
        assertEquals(0L, result.bytesUp)
        assertFalse(result.documentChanged)
        assertArrayEquals(newerLocalBytes, store.readBytesOrNull())
        assertEquals(remoteBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `local-ahead push-pull stores confirmed hash without reporting unchanged document`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(confirmedBytes, localBytes)))
        val phases = mutableListOf<SyncEnginePhase>()

        val result = SyncEngine(store, stateStore).sync(client, phases::add)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertEquals(localBytes.size.toLong(), result.bytesDown)
        assertEquals(localBytes.size.toLong(), result.bytesUp)
        assertFalse(result.documentChanged)
        assertEquals(
            listOf(SyncEnginePhase.PULLING, SyncEnginePhase.PUSHING, SyncEnginePhase.PULLING),
            phases,
        )
        assertEquals(2, client.pullCount)
        assertEquals(listOf(localBytes.toList()), client.pushes.map { it.toList() })
        assertArrayEquals(localBytes, store.readBytesOrNull())
        assertEquals(localBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `local hash mismatch pushes without dirty flag`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(confirmedBytes, localBytes)))

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.SUCCESS, result.outcome)
        assertEquals(listOf(localBytes.toList()), client.pushes.map { it.toList() })
        assertArrayEquals(localBytes, store.readBytesOrNull())
        assertEquals(localBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `merge result replaces local bytes with server result and reports MERGED`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val mergedBytes = bytes("merged")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(confirmedBytes, mergedBytes)))
        val phases = mutableListOf<SyncEnginePhase>()

        val result = SyncEngine(store, stateStore).sync(client, phases::add)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.MERGED, result.outcome)
        assertEquals(mergedBytes.size.toLong(), result.bytesDown)
        assertEquals(localBytes.size.toLong(), result.bytesUp)
        assertTrue(result.documentChanged)
        assertEquals(
            listOf(SyncEnginePhase.PULLING, SyncEnginePhase.PUSHING, SyncEnginePhase.PULLING),
            phases,
        )
        assertEquals(2, client.pullCount)
        assertArrayEquals(mergedBytes, store.readBytesOrNull())
        assertEquals(mergedBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `local matching remote hash confirms without upload when confirmed hash is old`() = runTest {
        val confirmedBytes = bytes("base")
        val currentBytes = bytes("current")
        val store = newFileStore()
        store.replaceWith(currentBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(currentBytes)))
        val phases = mutableListOf<SyncEnginePhase>()

        val result = SyncEngine(store, stateStore).sync(client, phases::add)

        assertEquals(
            SyncEngineResult(
                type = SyncType.PULL,
                outcome = SyncOutcome.NO_CHANGE,
                bytesDown = 0L,
                bytesUp = 0L,
                documentChanged = false,
            ),
            result,
        )
        assertEquals(listOf(SyncEnginePhase.PULLING), phases)
        assertEquals(1, client.pullCount)
        assertEquals(0, client.pushes.size)
        assertArrayEquals(currentBytes, store.readBytesOrNull())
        assertEquals(currentBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `local matching confirmed hash does not upload`() = runTest {
        val bytes = bytes("same")
        val store = newFileStore()
        store.replaceWith(bytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = bytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(bytes)))

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.NO_CHANGE, result.outcome)
        assertEquals(0L, result.bytesDown)
        assertEquals(0L, result.bytesUp)
        assertFalse(result.documentChanged)
        assertArrayEquals(bytes, store.readBytesOrNull())
        assertEquals(0, client.pushes.size)
    }

    @Test
    fun `preflight matching remote preserves newer local bytes written during pull`() = runTest {
        val localBytes = bytes("local")
        val newerLocalBytes = bytes("newer local")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = bytes("base").sha256Hex())
        val client = FakeRemoteDatabaseClient(
            pulls = ArrayDeque(listOf(localBytes)),
            beforePullReturn = {
                store.replaceWith(newerLocalBytes)
            },
        )

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(
            SyncEngineResult(
                type = SyncType.PULL,
                outcome = SyncOutcome.NO_CHANGE,
                bytesDown = 0L,
                bytesUp = 0L,
                documentChanged = false,
            ),
            result,
        )
        assertEquals(1, client.pullCount)
        assertEquals(0, client.pushes.size)
        assertArrayEquals(newerLocalBytes, store.readBytesOrNull())
        assertEquals(localBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `push failure preserves local bytes and hash`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(
            pulls = ArrayDeque(listOf(confirmedBytes)),
            pushException = IllegalStateException("push exploded"),
        )

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.FAILURE, result.outcome)
        assertEquals(0L, result.bytesDown)
        assertEquals(0L, result.bytesUp)
        assertFalse(result.documentChanged)
        assertEquals("push exploded", result.errorMessage)
        assertArrayEquals(localBytes, store.readBytesOrNull())
        assertEquals(confirmedBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `preflight pull failure preserves local bytes and hash`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(
            pulls = ArrayDeque(),
            pullExceptions = ArrayDeque(listOf(IllegalStateException("preflight exploded"))),
        )

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PULL, result.type)
        assertEquals(SyncOutcome.FAILURE, result.outcome)
        assertEquals(0L, result.bytesDown)
        assertEquals(0L, result.bytesUp)
        assertFalse(result.documentChanged)
        assertEquals("preflight exploded", result.errorMessage)
        assertEquals(0, client.pushes.size)
        assertArrayEquals(localBytes, store.readBytesOrNull())
        assertEquals(confirmedBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `confirm pull failure after successful push preserves local bytes and hash`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(
            pulls = ArrayDeque(listOf(confirmedBytes)),
            pullExceptions = ArrayDeque(listOf(null, IllegalStateException("confirm exploded"))),
        )

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.FAILURE, result.outcome)
        assertEquals(0L, result.bytesDown)
        assertEquals(localBytes.size.toLong(), result.bytesUp)
        assertFalse(result.documentChanged)
        assertEquals("confirm exploded", result.errorMessage)
        assertEquals(listOf(localBytes.toList()), client.pushes.map { it.toList() })
        assertArrayEquals(localBytes, store.readBytesOrNull())
        assertEquals(confirmedBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test(expected = CancellationException::class)
    fun `pull cancellation propagates instead of returning failure`() = runTest {
        val localBytes = bytes("local")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = localBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(
            pulls = ArrayDeque(),
            pullExceptions = ArrayDeque(listOf(CancellationException("cancelled"))),
        )

        SyncEngine(store, stateStore).sync(client)
    }

    @Test
    fun `push-pull preserves newer local bytes written during confirm pull`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val newerLocalBytes = bytes("newer local")
        val mergedBytes = bytes("merged")
        val store = newFileStore()
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(
            pulls = ArrayDeque(listOf(confirmedBytes, mergedBytes)),
            beforePullReturn = { pullCount ->
                if (pullCount == 2) {
                    store.replaceWith(newerLocalBytes)
                }
            },
        )

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.MERGED, result.outcome)
        assertEquals(mergedBytes.size.toLong(), result.bytesDown)
        assertEquals(localBytes.size.toLong(), result.bytesUp)
        assertFalse(result.documentChanged)
        assertEquals(listOf(localBytes.toList()), client.pushes.map { it.toList() })
        assertArrayEquals(newerLocalBytes, store.readBytesOrNull())
        assertEquals(mergedBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    @Test
    fun `push-pull preserves local bytes written during transactional confirm replace`() = runTest {
        val confirmedBytes = bytes("base")
        val localBytes = bytes("local")
        val newerLocalBytes = bytes("newer local")
        val mergedBytes = bytes("merged")
        val store = MutatingConditionalReplaceFileStore(
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache"),
            mutateToBytes = newerLocalBytes,
        )
        store.replaceWith(localBytes)
        val stateStore = FakeSyncStateStore(lastSyncedHash = confirmedBytes.sha256Hex())
        val client = FakeRemoteDatabaseClient(pulls = ArrayDeque(listOf(confirmedBytes, mergedBytes)))

        val result = SyncEngine(store, stateStore).sync(client)

        assertEquals(SyncType.PUSH_PULL, result.type)
        assertEquals(SyncOutcome.MERGED, result.outcome)
        assertEquals(mergedBytes.size.toLong(), result.bytesDown)
        assertEquals(localBytes.size.toLong(), result.bytesUp)
        assertFalse(result.documentChanged)
        assertEquals(listOf(localBytes.toList()), client.pushes.map { it.toList() })
        assertArrayEquals(newerLocalBytes, store.readBytesOrNull())
        assertEquals(mergedBytes.sha256Hex(), stateStore.lastSyncedHash)
    }

    private fun newFileStore(): DatabaseFileStore =
        DatabaseFileStore(
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache"),
        )

    private fun bytes(value: String): ByteArray = value.encodeToByteArray()

    private class ThrowingReplaceFileStore(
        filesDir: File,
        cacheDir: File,
    ) : DatabaseFileStore(filesDir, cacheDir) {
        var failReplacements = false

        override fun replaceWith(bytes: ByteArray) {
            if (failReplacements) throw IllegalStateException("replace exploded")
            super.replaceWith(bytes)
        }

        override fun replaceWithIfCurrent(expectedHash: String?, bytes: ByteArray): Boolean {
            if (failReplacements) throw IllegalStateException("replace exploded")
            return super.replaceWithIfCurrent(expectedHash, bytes)
        }
    }

    private class MutatingConditionalReplaceFileStore(
        filesDir: File,
        cacheDir: File,
        private val mutateToBytes: ByteArray,
    ) : DatabaseFileStore(filesDir, cacheDir) {
        var hasMutated = false

        override fun replaceWithIfCurrent(expectedHash: String?, bytes: ByteArray): Boolean {
            if (!hasMutated) {
                hasMutated = true
                replaceWith(mutateToBytes)
            }
            return super.replaceWithIfCurrent(expectedHash, bytes)
        }
    }

    private class FakeSyncStateStore(
        override var lastSyncedHash: String? = null,
        override var consecutiveFailures: Int = 0,
    ) : SyncStateStore {
        override fun reset() {
            lastSyncedHash = null
            consecutiveFailures = 0
        }
    }

    private class FakeRemoteDatabaseClient(
        private val pulls: ArrayDeque<ByteArray>,
        private val pullExceptions: ArrayDeque<Exception?> = ArrayDeque(),
        private val pushException: Exception? = null,
        private val beforePullReturn: (pullCount: Int) -> Unit = {},
    ) : RemoteDatabaseClient {
        val pushes = mutableListOf<ByteArray>()
        var pullCount = 0

        override suspend fun pull(): ByteArray {
            pullCount++
            if (pullExceptions.isNotEmpty()) {
                pullExceptions.removeFirst()?.let { throw it }
            }
            beforePullReturn(pullCount)
            return pulls.removeFirst()
        }

        override suspend fun push(bytes: ByteArray) {
            pushException?.let { throw it }
            pushes += bytes.copyOf()
        }
    }
}
