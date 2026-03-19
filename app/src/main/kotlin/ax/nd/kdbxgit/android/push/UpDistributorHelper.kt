package ax.nd.kdbxgit.android.push

import android.content.Context
import ax.nd.kdbxgit.android.KdbxGitApplication
import ax.nd.kdbxgit.android.sync.WebDavClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.unifiedpush.android.connector.UnifiedPush

private val logger = KotlinLogging.logger {}

/**
 * Selects a UP distributor and registers for push, handling both the deeplink path (external
 * distributors) and the broadcast-receiver path (embedded FCM distributor).
 *
 * [tryUseCurrentOrDefaultDistributor] uses an `unifiedpush://link` deeplink to discover and
 * save a distributor. The embedded FCM distributor does not register a deeplink Activity, so
 * the callback returns `false` even when it is available. In that case we fall back to
 * [getDistributors], which scans for `ACTION_REGISTER` broadcast receivers and therefore
 * finds the embedded distributor, then save the first one found and register.
 *
 * The VAPID public key is fetched from the server before registering. The embedded FCM
 * distributor requires it, and the app skips UP registration if the fetch fails.
 */
suspend fun registerUpDistributor(context: Context) {
    val config = (context.applicationContext as KdbxGitApplication).settingsRepository.serverConfig.value
    if (config == null) {
        logger.info { "Skipping UP registration: no server config saved yet" }
        return
    }

    val vapid = try {
        val key = WebDavClient(config).fetchVapidPublicKey()
        logger.info { "Fetched VAPID public key from server" }
        key
    } catch (e: Exception) {
        logger.warn(e) { "Failed to fetch VAPID key — skipping push registration" }
        return
    }

    UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
        if (success) {
            val distributor = UnifiedPush.getSavedDistributor(context)
            logger.info { "UP distributor selected via deeplink: $distributor — registering" }
            UnifiedPush.register(context, vapid = vapid)
        } else {
            // Deeplink found nothing — try broadcast-receiver discovery (covers embedded FCM).
            val distributors = UnifiedPush.getDistributors(context)
            val chosen = distributors.firstOrNull()
            if (chosen != null) {
                logger.info { "UP distributor selected via broadcast scan: $chosen — registering" }
                UnifiedPush.saveDistributor(context, chosen)
                UnifiedPush.register(context, vapid = vapid)
            } else {
                logger.warn { "No UP distributor available" }
            }
        }
    }
}
