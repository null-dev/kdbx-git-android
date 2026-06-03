package ax.nd.kdbxgit.android.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerTest {

    @Test
    fun `one-time sync work appends instead of replacing in-flight work`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, SyncWorker.ONE_TIME_WORK_POLICY)
    }
}
