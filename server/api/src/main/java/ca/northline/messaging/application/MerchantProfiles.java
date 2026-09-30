package ca.northline.messaging.application;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what messaging needs to know about a business — its type (portal) and tier (support SLA), answered by
 * {@code merchants.api.MerchantDirectory} (S-37).
 */
public interface MerchantProfiles {

    record Profile(String type, @Nullable String tier) {
        public boolean masterTier() {
            return "master".equals(tier);
        }
    }

    Optional<Profile> profile(String merchantId);
}
