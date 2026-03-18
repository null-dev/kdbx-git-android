package ax.nd.kdbxgit.android.sync

import java.security.MessageDigest

fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

fun ByteArray.sha256Hex(): String = sha256().joinToString("") { "%02x".format(it) }
