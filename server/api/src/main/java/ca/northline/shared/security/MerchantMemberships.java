package ca.northline.shared.security;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port: who belongs to which merchant with which role. Implemented by the merchants module
 * (reads {@code merchants.merchant_members}); used by {@link MerchantAccess} and the local dev-auth filter.
 */
public interface MerchantMemberships {

    record Membership(String merchantId, MerchantRole role) {}

    Optional<MerchantRole> roleOf(String merchantId, String userId);

    List<Membership> membershipsOf(String userId);
}
