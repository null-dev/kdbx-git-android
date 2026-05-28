package ax.nd.kdbxgit.android.provider

import ax.nd.kdbxgit.android.sync.sha256Hex
import java.io.File

internal fun stagedDatabaseChangedFromBase(staged: File, baseHash: String): Boolean =
    staged.readBytes().sha256Hex() != baseHash
