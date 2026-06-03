package ax.nd.kdbxgit.android.sync

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `metadata reports missing database`() {
        val store = newStore()

        val metadata = store.metadata()

        assertFalse(metadata.exists)
        assertEquals(0L, metadata.size)
        assertEquals(0L, metadata.lastModified)
    }

    @Test
    fun `replace writes bytes and metadata reports file`() {
        val store = newStore()
        val bytes = byteArrayOf(1, 2, 3)

        store.replaceWith(bytes)

        assertArrayEquals(bytes, store.dbFile.readBytes())
        val metadata = store.metadata()
        assertTrue(metadata.exists)
        assertEquals(bytes.size.toLong(), metadata.size)
        assertTrue(metadata.lastModified > 0L)
    }

    @Test
    fun `conditional replace updates live bytes when expected hash matches`() {
        val store = newStore()
        val original = byteArrayOf(1, 2, 3)
        val replacement = byteArrayOf(4, 5, 6)
        store.replaceWith(original)

        val replaced = store.replaceWithIfCurrent(original.sha256Hex(), replacement)

        assertTrue(replaced)
        assertArrayEquals(replacement, store.dbFile.readBytes())
    }

    @Test
    fun `conditional replace reports false when replacement bytes already match live bytes`() {
        val store = newStore()
        val bytes = byteArrayOf(1, 2, 3)
        store.replaceWith(bytes)
        val originalModified = store.dbFile.lastModified()

        val replaced = store.replaceWithIfCurrent(bytes.sha256Hex(), bytes)

        assertFalse(replaced)
        assertArrayEquals(bytes, store.dbFile.readBytes())
        assertEquals(originalModified, store.dbFile.lastModified())
    }

    @Test
    fun `conditional replace preserves live bytes when expected hash is stale`() {
        val store = newStore()
        val live = byteArrayOf(1, 2, 3)
        val replacement = byteArrayOf(4, 5, 6)
        store.replaceWith(live)

        val replaced = store.replaceWithIfCurrent(byteArrayOf(9).sha256Hex(), replacement)

        assertFalse(replaced)
        assertArrayEquals(live, store.dbFile.readBytes())
    }

    @Test
    fun `sync replacements do not emit local change events`() = runTest {
        val store = newStore()
        val localChange = async { store.localChanges.first() }
        runCurrent()

        store.replaceWith(byteArrayOf(1, 2, 3))
        val replaced = store.replaceWithIfCurrent(
            expectedHash = byteArrayOf(1, 2, 3).sha256Hex(),
            bytes = byteArrayOf(4, 5, 6),
        )

        assertTrue(replaced)
        assertFalse(localChange.isCompleted)
        localChange.cancel()
    }

    @Test(expected = IOException::class)
    fun `conditional replace throws when rename fails`() {
        val store = RenameFailingStore()
        val original = byteArrayOf(1, 2, 3)
        store.replaceWith(original)
        store.failRenames = true

        store.replaceWithIfCurrent(original.sha256Hex(), byteArrayOf(4, 5, 6))
    }

    @Test
    fun `clear deletes database`() {
        val store = newStore()
        store.replaceWith(byteArrayOf(1, 2, 3))

        store.clear()

        assertFalse(store.dbFile.exists())
    }

    @Test
    fun `unchanged staging commit reports false and keeps live bytes`() {
        val store = newStore()
        val bytes = byteArrayOf(1, 2, 3)
        store.replaceWith(bytes)
        val staged = store.createStagingSnapshotForTest()

        val changed = store.commitStagingForTest(staged)

        assertFalse(changed)
        assertArrayEquals(bytes, store.dbFile.readBytes())
        assertFalse(staged.file.exists())
    }

    @Test
    fun `changed same-size staging commit replaces live bytes`() {
        val store = newStore()
        store.replaceWith(byteArrayOf(1, 2, 3))
        val staged = store.createStagingSnapshotForTest()
        staged.file.writeBytes(byteArrayOf(1, 2, 4))

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        assertArrayEquals(byteArrayOf(1, 2, 4), store.dbFile.readBytes())
        assertFalse(staged.file.exists())
    }

    @Test
    fun `changed staging commit emits local change event to multiple collectors`() = runTest {
        val store = newStore()
        val firstCollector = async { store.localChanges.first() }
        val secondCollector = async { store.localChanges.first() }
        runCurrent()
        val staged = store.createStagingSnapshotForTest()
        staged.file.writeBytes(byteArrayOf(1))

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        firstCollector.await()
        secondCollector.await()
    }

    @Test
    fun `unchanged staging commit does not emit local change event`() = runTest {
        val store = newStore()
        val localChange = async { store.localChanges.first() }
        runCurrent()
        val bytes = byteArrayOf(1, 2, 3)
        store.replaceWith(bytes)
        val staged = store.createStagingSnapshotForTest()

        val changed = store.commitStagingForTest(staged)

        assertFalse(changed)
        assertFalse(localChange.isCompleted)
        localChange.cancel()
    }

    @Test
    fun `new collectors do not receive old local change events`() = runTest {
        val store = newStore()
        val staged = store.createStagingSnapshotForTest()
        staged.file.writeBytes(byteArrayOf(1))
        store.commitStagingForTest(staged)

        val localChange = async { store.localChanges.drop(1).first() }
        runCurrent()

        assertFalse(localChange.isCompleted)
        localChange.cancel()
    }

    @Test
    fun `truncate write staging starts empty even when live database has bytes`() {
        val store = newStore()
        store.replaceWith(byteArrayOf(1, 2, 3))

        val staged = store.createStagingSnapshotForTest(
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
        )

        assertArrayEquals(byteArrayOf(), staged.file.readBytes())
    }

    @Test
    fun `truncate write commit reports changed when live database had bytes`() {
        val store = newStore()
        store.replaceWith(byteArrayOf(1, 2, 3))
        val staged = store.createStagingSnapshotForTest(
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
        )

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        assertArrayEquals(byteArrayOf(), store.dbFile.readBytes())
        assertFalse(staged.file.exists())
    }

    @Test
    fun `write-only parsed mode opens staging write-only`() {
        val sanitizedMode = DatabaseFileStore.sanitizeWritableModeForTest(
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE,
        )

        assertEquals(
            ParcelFileDescriptor.MODE_WRITE_ONLY,
            sanitizedMode and WRITABLE_ACCESS_MODE_MASK,
        )
    }

    @Test
    fun `write-only staging can commit changed bytes`() {
        val store = newStore()
        store.replaceWith(byteArrayOf(1, 2, 3))
        val staged = store.createStagingSnapshotForTest(ParcelFileDescriptor.MODE_WRITE_ONLY)
        staged.file.writeBytes(byteArrayOf(4, 5, 6))

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        assertArrayEquals(byteArrayOf(4, 5, 6), store.dbFile.readBytes())
        assertFalse(staged.file.exists())
    }

    @Test
    fun `staged local commit wins because sync server merge handles remote reconciliation`() {
        val store = newStore()
        val originalBytes = byteArrayOf(1, 2, 3)
        val pulledBytes = byteArrayOf(4, 5, 6)
        val savedBytes = byteArrayOf(7, 8, 9)
        store.replaceWith(originalBytes)
        val staged = store.createStagingSnapshotForTest()
        store.replaceWith(pulledBytes)
        staged.file.writeBytes(savedBytes)

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        assertArrayEquals(savedBytes, store.dbFile.readBytes())
        assertFalse(staged.file.exists())
    }

    @Test
    fun `empty base can commit non-empty staged bytes`() {
        val store = newStore()
        val staged = store.createStagingSnapshotForTest()
        staged.file.writeBytes(byteArrayOf(1))

        val changed = store.commitStagingForTest(staged)

        assertTrue(changed)
        assertArrayEquals(byteArrayOf(1), store.dbFile.readBytes())
        assertFalse(staged.file.exists())
    }

    @Test(expected = IOException::class)
    fun `staging commit throws when rename fails`() {
        val store = RenameFailingStore()
        val staged = store.createStagingSnapshotForTest()
        staged.file.writeBytes(byteArrayOf(1))
        store.failRenames = true

        store.commitStagingForTest(staged)
    }

    private fun newStore(): DatabaseFileStore =
        DatabaseFileStore(
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache"),
        )

    private fun RenameFailingStore(): RenameFailingDatabaseFileStore =
        RenameFailingDatabaseFileStore(
            filesDir = tmp.newFolder("files"),
            cacheDir = tmp.newFolder("cache"),
        )

    private class RenameFailingDatabaseFileStore(
        filesDir: java.io.File,
        cacheDir: java.io.File,
    ) : DatabaseFileStore(filesDir, cacheDir) {
        var failRenames = false

        override fun renameToLiveDatabase(source: java.io.File): Boolean =
            if (failRenames) false else super.renameToLiveDatabase(source)
    }

    private companion object {
        private const val WRITABLE_ACCESS_MODE_MASK =
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_READ_WRITE
    }
}
