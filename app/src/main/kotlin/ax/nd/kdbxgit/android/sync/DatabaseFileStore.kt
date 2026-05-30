package ax.nd.kdbxgit.android.sync

import android.os.Handler
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock

open class DatabaseFileStore(
    private val filesDir: File,
    private val cacheDir: File,
) {

    val dbFile: File = File(filesDir, "database.kdbx")

    private val lock = ReentrantReadWriteLock()
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    data class Metadata(
        val exists: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    data class StagedWrite(
        val file: File,
        val baseHash: String,
    )

    data class Snapshot(
        val bytes: ByteArray?,
        val hash: String?,
    )

    fun metadata(): Metadata {
        lock.readLock().lock()
        return try {
            if (!dbFile.exists()) {
                Metadata(exists = false, size = 0L, lastModified = 0L)
            } else {
                Metadata(
                    exists = true,
                    size = dbFile.length(),
                    lastModified = dbFile.lastModified(),
                )
            }
        } finally {
            lock.readLock().unlock()
        }
    }

    fun readBytesOrNull(): ByteArray? {
        lock.readLock().lock()
        return try {
            if (dbFile.exists()) dbFile.readBytes() else null
        } finally {
            lock.readLock().unlock()
        }
    }

    fun hashOrNull(): String? = readBytesOrNull()?.sha256Hex()

    fun snapshot(): Snapshot {
        lock.readLock().lock()
        return try {
            val bytes = if (dbFile.exists()) dbFile.readBytes() else null
            Snapshot(bytes = bytes, hash = bytes?.sha256Hex())
        } finally {
            lock.readLock().unlock()
        }
    }

    fun clear() {
        lock.writeLock().lock()
        try {
            dbFile.delete()
        } finally {
            lock.writeLock().unlock()
        }
    }

    open fun replaceWith(bytes: ByteArray) {
        lock.writeLock().lock()
        try {
            replaceLocked(bytes)
        } finally {
            lock.writeLock().unlock()
        }
    }

    open fun replaceWithIfCurrent(expectedHash: String?, bytes: ByteArray): Boolean {
        lock.writeLock().lock()
        return try {
            val currentBytes = if (dbFile.exists()) dbFile.readBytes() else null
            val currentHash = currentBytes?.sha256Hex()
            if (currentHash != expectedHash) {
                false
            } else {
                replaceLocked(bytes)
                true
            }
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
        parsedMode: Int,
        handler: Handler,
    ): ParcelFileDescriptor {
        val staged = createStagingSnapshot(parsedMode)
        return ParcelFileDescriptor.open(
            staged.file,
            sanitizeWritableMode(parsedMode),
            handler,
        ) { error ->
            if (error == null) {
                commitStaging(staged)
            } else {
                staged.file.delete()
            }
        }
    }

    internal fun createStagingSnapshotForTest(
        parsedMode: Int = ParcelFileDescriptor.MODE_READ_WRITE,
    ): StagedWrite = createStagingSnapshot(parsedMode)

    internal fun commitStagingForTest(staged: StagedWrite): Boolean = commitStaging(staged)

    private fun createStagingSnapshot(parsedMode: Int): StagedWrite {
        lock.readLock().lock()
        return try {
            cacheDir.mkdirs()
            val staging = File(cacheDir, "staged_${System.nanoTime()}.kdbx")
            val truncate = parsedMode and ParcelFileDescriptor.MODE_TRUNCATE != 0
            val baseHash = if (dbFile.exists()) {
                dbFile.readBytes().sha256Hex()
            } else {
                byteArrayOf().sha256Hex()
            }

            if (truncate) {
                staging.createNewFile()
            } else if (dbFile.exists()) {
                dbFile.copyTo(staging, overwrite = true)
            } else {
                staging.createNewFile()
            }
            StagedWrite(
                file = staging,
                baseHash = baseHash,
            )
        } finally {
            lock.readLock().unlock()
        }
    }

    private fun commitStaging(staged: StagedWrite): Boolean {
        var changed = false
        lock.writeLock().lock()
        try {
            val stagedBytes = staged.file.readBytes()
            if (stagedBytes.sha256Hex() == staged.baseHash) {
                staged.file.delete()
            } else {
                // A changed KeePass save intentionally replaces the live file even if
                // sync pulled newer bytes while the FD was open. The next sync push
                // lets the server merge reconcile that state.
                filesDir.mkdirs()
                renameToLiveDatabaseOrThrow(staged.file)
                changed = true
            }
        } finally {
            lock.writeLock().unlock()
        }
        if (changed) {
            _localChanges.tryEmit(Unit)
        }
        return changed
    }

    private fun replaceLocked(bytes: ByteArray) {
        filesDir.mkdirs()
        val temp = File(filesDir, "database_${System.nanoTime()}.tmp")
        try {
            temp.writeBytes(bytes)
            renameToLiveDatabaseOrThrow(temp)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    protected open fun renameToLiveDatabase(source: File): Boolean = source.renameTo(dbFile)

    private fun renameToLiveDatabaseOrThrow(source: File) {
        if (!renameToLiveDatabase(source)) {
            throw IOException("Failed to replace database.kdbx")
        }
    }

    internal companion object {
        private val ACCESS_MODE_MASK =
            ParcelFileDescriptor.MODE_READ_ONLY or
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_READ_WRITE

        fun sanitizeWritableModeForTest(parsedMode: Int): Int = sanitizeWritableMode(parsedMode)

        private fun sanitizeWritableMode(parsedMode: Int): Int {
            val accessMode = parsedMode and ACCESS_MODE_MASK
            require(accessMode == ParcelFileDescriptor.MODE_WRITE_ONLY ||
                accessMode == ParcelFileDescriptor.MODE_READ_WRITE) {
                "Mode is not writable"
            }
            return parsedMode
        }
    }
}
