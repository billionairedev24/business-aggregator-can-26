package ca.northline.merchants.application;

import ca.northline.merchants.domain.OwnerIdentityCheck;
import ca.northline.merchants.domain.PrincipalRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: the owners who need identity verification and their {@code owner_identity_checks} rows. */
public interface OwnerIdentityStore {

    /**
     * The principals pointed at the KYC row (at or above the structure's threshold), largest share first, each with
     * its check when one was started.
     */
    List<Owner> owners(String merchantId);

    /** The check holding this Stripe session, locked for update. */
    Optional<OwnerIdentityCheck> lockBySession(String sessionId);

    Optional<OwnerIdentityCheck> find(String merchantId, String checkId);

    void save(OwnerIdentityCheck check);

    /** Marks the principal as the signed-in user ("this is me"). */
    void bindUser(String merchantId, String principalId, String userId);

    /** The business's Stripe Connect account, when it has one. */
    Optional<String> stripeAccount(String merchantId);

    record Owner(
            String principalId,
            String legalName,
            PrincipalRole role,
            @Nullable BigDecimal ownershipPct,
            @Nullable String userId,
            @Nullable OwnerIdentityCheck check) {}
}
