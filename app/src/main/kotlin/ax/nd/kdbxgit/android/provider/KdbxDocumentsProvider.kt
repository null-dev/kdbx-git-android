package ax.nd.kdbxgit.android.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.R
import ax.nd.kdbxgit.android.sync.SyncRepository
import ax.nd.kdbxgit.android.sync.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Exposes a single KDBX document via Android's Storage Access Framework.
 *
 * Security model: the provider is exported (required by SAF) but all URI grants
 * are mediated by the system file picker. No app can obtain a valid URI without
 * an explicit user gesture — there are no guessable paths and no ambient access.
 *
 * Concurrency:
 *  - Multiple concurrent readers are allowed (shared read lock, held briefly).
 *  - A write commit acquires the exclusive write lock for the duration of the
 *    atomic rename only — it does NOT hold the lock while the client is editing.
 *  - [SyncRepository.syncMutex] serialises sync runs; a write arriving mid-sync
 *    sets the dirty flag and the subsequent sync run picks it up.
 */
class KdbxDocumentsProvider : DocumentsProvider() {

    private val syncRepository: SyncRepository
        get() = (context!!.applicationContext as KdbxGitApplication).syncRepository

    // Background thread that receives the ParcelFileDescriptor.OnCloseListener callback.
    private val handlerThread = HandlerThread("kdbx-provider-io")
    private lateinit var handler: Handler

    // Read lock: held briefly while opening the live file or copying it to staging.
    // Write lock: held briefly during the atomic commit rename.
    private val lock = ReentrantReadWriteLock()

    override fun onCreate(): Boolean {
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        return true
    }

    // ── queryRoots ────────────────────────────────────────────────────────

    override fun queryRoots(projection: Array<out String>?): Cursor {
        return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID,     ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, SyncRepository.DB_DOC_ID)
                add(Root.COLUMN_TITLE,       "kdbx-git sync")
                add(Root.COLUMN_ICON,        R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS,       Root.FLAG_LOCAL_ONLY)
                add(Root.COLUMN_MIME_TYPES,  KDBX_MIME_TYPE)
            }
        }
    }

    // ── queryDocument / queryChildDocuments ───────────────────────────────

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also { cursor ->
            cursor.setNotificationUri(context!!.contentResolver, docUri())
            addDocumentRow(cursor)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also { cursor ->
            cursor.setNotificationUri(context!!.contentResolver, docUri())
            addDocumentRow(cursor)
        }
    }

    private fun addDocumentRow(cursor: MatrixCursor) {
        val dbFile = syncRepository.dbFile
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID,   SyncRepository.DB_DOC_ID)
            add(Document.COLUMN_DISPLAY_NAME,  "database.kdbx")
            add(Document.COLUMN_MIME_TYPE,     KDBX_MIME_TYPE)
            add(Document.COLUMN_FLAGS,         Document.FLAG_SUPPORTS_WRITE)
            add(Document.COLUMN_SIZE,          if (dbFile.exists()) dbFile.length() else 0L)
            add(Document.COLUMN_LAST_MODIFIED, if (dbFile.exists()) dbFile.lastModified() else 0L)
        }
    }

    // ── openDocument ──────────────────────────────────────────────────────

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val dbFile = syncRepository.dbFile
        val parsedMode = ParcelFileDescriptor.parseMode(mode ?: "r")

        if (parsedMode == ParcelFileDescriptor.MODE_READ_ONLY) {
            // Read-only path: hold the read lock briefly while opening the file so we
            // don't race with a commit rename. Once the FD is returned the client holds
            // an open inode; any subsequent atomic rename won't affect it (Unix semantics).
            lock.readLock().lock()
            return try {
                if (!dbFile.exists()) throw FileNotFoundException("Database not yet synced")
                ParcelFileDescriptor.open(dbFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                lock.readLock().unlock()
            }
        }

        // Write path: copy the live file into a per-request staging file so the caller
        // can do random-access read+write freely. On close, the staging file is committed
        // atomically and a WRITE-triggered sync is enqueued.
        val staging = File(context!!.cacheDir, "staged_${System.nanoTime()}.kdbx")
        lock.readLock().lock()
        try {
            if (dbFile.exists()) dbFile.copyTo(staging, overwrite = true)
            else staging.createNewFile()
        } finally {
            lock.readLock().unlock()
        }

        return ParcelFileDescriptor.open(
            staging,
            ParcelFileDescriptor.MODE_READ_WRITE,
            handler,
        ) { error ->
            if (error == null) commitStaging(staging) else staging.delete()
        }
    }

    // ── Staging-file commit ───────────────────────────────────────────────

    /**
     * Called on the [handler] thread when the client closes its write FD cleanly.
     * Atomically replaces the live database, marks it dirty, notifies observers,
     * and enqueues a sync.
     */
    private fun commitStaging(staging: File) {
        val dbFile = syncRepository.dbFile

        lock.writeLock().lock()
        try {
            if (!staging.renameTo(dbFile)) {
                // Cross-filesystem rename would fail — shouldn't happen (both paths are
                // on internal storage) but handle it safely.
                dbFile.writeBytes(staging.readBytes())
                staging.delete()
            }
        } finally {
            lock.writeLock().unlock()
        }

        syncRepository.markDirty()

        // Notify any open cursors (e.g. the system picker) that metadata changed.
        context!!.contentResolver.notifyChange(docUri(), null)

        // Trigger a push sync. SyncRepository.syncMutex ensures at most one sync runs
        // at a time; concurrent writes will coalesce into the next run.
        CoroutineScope(Dispatchers.IO).launch {
            syncRepository.sync(SyncTrigger.WRITE)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun docUri() = DocumentsContract.buildDocumentUri(
        SyncRepository.DOCUMENTS_AUTHORITY, SyncRepository.DB_DOC_ID
    )

    // ── Constants ─────────────────────────────────────────────────────────

    companion object {
        private const val ROOT_ID       = "kdbx-git-root"
        private const val KDBX_MIME_TYPE = "application/octet-stream"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
