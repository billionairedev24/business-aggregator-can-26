package ca.northline.merchants.application;

import ca.northline.merchants.domain.IdentityMatch;
import ca.northline.merchants.domain.IdentitySessionState;
import ca.northline.merchants.domain.OwnerIdentityCheck.Delivery;
import ca.northline.merchants.domain.PrincipalRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Use cases of the owners' identity verification (S-22): the Verification step's "Identity (Stripe KYC)" row. */
public final class OwnerIdentity {
    private OwnerIdentity() {}

    public static final String EMAIL_FIELD = "email";
    public static final String EMAIL_REQUIRED = "Enter the owner's email address.";
    public static final String EMAIL_FORMAT = "That doesn't look like an email address.";

    /** Every owner who needs verification, with where they are. */
    public interface ListOwners {
        List<OwnerView> owners(String merchantId, String userId);
    }

    /**
     * Opens a Stripe Identity session for one owner: the signed-in owner gets the hosted-flow URL back
     * ({@code delivery = self}); anyone else gets it by email.
     */
    public interface StartOwnerVerification {
        record Command(
                String merchantId,
                String principalId,
                Delivery delivery,
                @Nullable String email,
                String userId) {}

        /** @param url Stripe's hosted flow for {@code self}; null for emailed links */
        record Started(OwnerView owner, @Nullable String url) {}

        Started start(Command command);
    }

    /** A Stripe Identity webhook ({@code identity.verification_session.*}) reached the merchants module. */
    public interface ApplyIdentitySession {
        record Update(
                String sessionId,
                IdentitySessionState state,
                @Nullable String lastError,
                Instant stripeCreated) {}

        void apply(Update update);
    }

    /**
     * @param status {@code not_started | pending | processing | verified | retry | review | canceled}
     * @param you this principal is the signed-in user
     * @param emailMasked where the last link went ({@code r***@example.com})
     * @param lastError Stripe's code when the owner must try again
     */
    public record OwnerView(
            String principalId,
            String legalName,
            PrincipalRole role,
            @Nullable BigDecimal ownershipPct,
            boolean you,
            String status,
            @Nullable Delivery delivery,
            @Nullable String emailMasked,
            @Nullable String lastError,
            @Nullable IdentityMatch nameMatch,
            @Nullable IdentityMatch dobMatch,
            int attempts,
            @Nullable Instant updatedAt) {}
}
