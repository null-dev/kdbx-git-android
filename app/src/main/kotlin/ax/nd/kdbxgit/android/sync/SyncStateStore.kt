package ax.nd.kdbxgit.android.sync

import android.content.Context
import android.content.SharedPreferences

interface SyncStateStore {
    var lastSyncedHash: String?
    var consecutiveFailures: Int
    fun reset()
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

    override var consecutiveFailures: Int
        get() = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)
        set(value) {
            prefs.edit().putInt(KEY_CONSECUTIVE_FAILURES, value).apply()
        }

    override fun reset() {
        prefs.edit()
            .remove(KEY_LAST_HASH)
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "kdbx_git_sync_state"
        private const val KEY_LAST_HASH = "last_synced_hash"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"

        fun from(context: Context): SharedPreferencesSyncStateStore =
            SharedPreferencesSyncStateStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
