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
import ax.nd.kdbxgit.android.sync.SyncWorker

/**
 * Exposes a single KDBX document via Android's Storage Access Framework.
 *
 * Security model: the provider is exported (required by SAF) but all URI grants
 * are mediated by the system file picker. No app can obtain a valid URI without
 * an explicit user gesture — there are no guessable paths and no ambient access.
 *
 * File access, staging, hashing, and locking live in
 * [ax.nd.kdbxgit.android.sync.DatabaseFileStore]. This provider adapts SAF calls
 * into repository/store operations and emits notifications after changed writes.
 */
class KdbxDocumentsProvider : DocumentsProvider() {

    private val syncRepository: SyncRepository
        get() = (context!!.applicationContext as KdbxGitApplication).syncRepository

    // Background thread that receives the ParcelFileDescriptor.OnCloseListener callback.
    private val handlerThread = HandlerThread("kdbx-provider-io")
    private lateinit var handler: Handler

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
                add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
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
            when (documentId) {
                ROOT_DOC_ID -> addRootDirRow(cursor)
                else        -> addFileRow(cursor)
            }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).also { cursor ->
            cursor.setNotificationUri(context!!.contentResolver, docUri())
            addFileRow(cursor)
        }
    }

    private fun addRootDirRow(cursor: MatrixCursor) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID,   ROOT_DOC_ID)
            add(Document.COLUMN_DISPLAY_NAME,  "kdbx-git sync")
            add(Document.COLUMN_MIME_TYPE,     Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS,         0)
            add(Document.COLUMN_SIZE,          0L)
            add(Document.COLUMN_LAST_MODIFIED, 0L)
        }
    }

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

    // ── openDocument ──────────────────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun docUri() = DocumentsContract.buildDocumentUri(
        SyncRepository.DOCUMENTS_AUTHORITY, SyncRepository.DB_DOC_ID
    )

    // ── Constants ─────────────────────────────────────────────────────────

    companion object {
        private const val ROOT_ID        = "kdbx-git-root"
        private const val ROOT_DOC_ID    = "root"
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
