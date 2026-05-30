package ax.nd.kdbxgit.android.sync

import android.os.Handler
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.locks.ReentrantReadWriteLock

open class DatabaseFileStore(
    private val filesDir: File,
    private val cacheDir: File,
) {

    val dbFile: File = File(filesDir, "database.kdbx")

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
            filesDir.mkdirs()
            val temp = File(filesDir, "database_${System.nanoTime()}.tmp")
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(dbFile)) {
                    dbFile.writeBytes(bytes)
                    temp.delete()
                }
            } finally {
                if (temp.exists()) temp.delete()
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
        handler: Handler,
        onCommitted: (Boolean) -> Unit,
    ): ParcelFileDescriptor {
        // Keep provider behavior consistent for every writable SAF mode: callers get
        // a read/write copy of the current database and commit it on close.
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
        lock.readLock().lock()
        return try {
            cacheDir.mkdirs()
            val staging = File(cacheDir, "staged_${System.nanoTime()}.kdbx")
            if (dbFile.exists()) {
                dbFile.copyTo(staging, overwrite = true)
            } else {
                staging.createNewFile()
            }
            StagedWrite(
                file = staging,
                baseHash = staging.readBytes().sha256Hex(),
            )
        } finally {
            lock.readLock().unlock()
        }
    }

    private fun commitStaging(staged: StagedWrite): Boolean {
        lock.writeLock().lock()
        return try {
            val stagedBytes = staged.file.readBytes()
            if (stagedBytes.sha256Hex() == staged.baseHash) {
                staged.file.delete()
                false
            } else {
                // A changed KeePass save intentionally replaces the live file even if
                // sync pulled newer bytes while the FD was open; dirty tracking and
                // the server merge reconcile that state on the next push.
                filesDir.mkdirs()
                if (!staged.file.renameTo(dbFile)) {
                    dbFile.writeBytes(stagedBytes)
                    staged.file.delete()
                }
                true
            }
        } finally {
            lock.writeLock().unlock()
        }
    }
}
