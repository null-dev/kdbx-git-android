package ax.nd.kdbxgit.android.sync

import java.io.IOException

/** Thrown when the server returns a non-2xx response. */
class WebDavException(val statusCode: Int, message: String) : IOException("HTTP $statusCode: $message")
