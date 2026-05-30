package ax.nd.kdbxgit.android.sync

interface RemoteDatabaseClient {
    suspend fun pull(): ByteArray
    suspend fun push(bytes: ByteArray)
}
