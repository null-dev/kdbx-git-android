package ax.nd.kdbxgit.android.provider

import ax.nd.kdbxgit.android.sync.sha256Hex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagedDatabaseDiffTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `staged file matching its open snapshot is unchanged`() {
        val staged = tmp.newFile("staged.kdbx").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val baseHash = staged.readBytes().sha256Hex()

        assertFalse(stagedDatabaseChangedFromBase(staged, baseHash))
    }

    @Test
    fun `same-size staged file with different bytes is changed`() {
        val baseHash = byteArrayOf(1, 2, 3).sha256Hex()
        val staged = tmp.newFile("staged.kdbx").also { it.writeBytes(byteArrayOf(1, 2, 4)) }

        assertTrue(stagedDatabaseChangedFromBase(staged, baseHash))
    }

    @Test
    fun `non-empty staged file differs from empty open snapshot`() {
        val baseHash = byteArrayOf().sha256Hex()
        val staged = tmp.newFile("staged.kdbx").also { it.writeBytes(byteArrayOf(1)) }

        assertTrue(stagedDatabaseChangedFromBase(staged, baseHash))
    }

    @Test
    fun `empty staged file matches empty open snapshot`() {
        val baseHash = byteArrayOf().sha256Hex()
        val staged = tmp.newFile("staged.kdbx")

        assertFalse(stagedDatabaseChangedFromBase(staged, baseHash))
    }
}
