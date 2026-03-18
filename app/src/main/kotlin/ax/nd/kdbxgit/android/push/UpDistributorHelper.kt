package ax.nd.kdbxgit.android.push

import android.content.Context
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
 */
fun registerUpDistributor(context: Context) {
    UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
        if (success) {
            val distributor = UnifiedPush.getSavedDistributor(context)
            logger.info { "UP distributor selected via deeplink: $distributor — registering" }
            UnifiedPush.register(context)
        } else {
            // Deeplink found nothing — try broadcast-receiver discovery (covers embedded FCM).
            val distributors = UnifiedPush.getDistributors(context)
            val chosen = distributors.firstOrNull()
            if (chosen != null) {
                logger.info { "UP distributor selected via broadcast scan: $chosen — registering" }
                UnifiedPush.saveDistributor(context, chosen)
                UnifiedPush.register(context)
            } else {
                logger.warn { "No UP distributor available" }
            }
        }
    }
}
