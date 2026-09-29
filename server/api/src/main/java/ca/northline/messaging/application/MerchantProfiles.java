package ca.northline.messaging.application;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what messaging needs to know about a business — its type (portal) and tier (support SLA). The
 * merchants module has no public query for it yet, so the adapter reads {@code merchants.merchants} (DECISIONS.md).
 */
public interface MerchantProfiles {

    record Profile(String type, @Nullable String tier) {
        public boolean masterTier() {
            return "master".equals(tier);
        }
    }

    Optional<Profile> profile(String merchantId);
}
