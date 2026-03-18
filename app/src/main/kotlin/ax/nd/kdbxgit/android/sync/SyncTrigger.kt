package ax.nd.kdbxgit.android.sync

enum class SyncTrigger {
    /** User pressed "Sync now" in the UI. */
    MANUAL,
    /** Local database write detected by KdbxDocumentsProvider. */
    WRITE,
    /** Network became available after an offline period. */
    CONNECTIVITY,
    /** WorkManager periodic job fired. */
    PERIODIC,
    /** UnifiedPush notification received (future). */
    PUSH,
}
