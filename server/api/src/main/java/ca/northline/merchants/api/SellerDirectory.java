package ca.northline.merchants.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Businesses as the console's sellers directory and seller detail see them (S-82): profile, categories, the
 * verifications that need attention and the staff oversight trail. Codes are the lower-case column values.
 */
public interface SellerDirectory {

    /**
     * Businesses in scope that applied (not mere applicants still filling the form), by name; {@code q} matches the
     * display or legal name (case-insensitive). At most {@code limit}.
     */
    List<Seller> sellers(MerchantScope scope, @Nullable String q, int limit);

    /** One business with every verification and the oversight trail, newest first. */
    Optional<SellerFile> seller(String merchantId);

    /**
     * @param categoryIds approved or requested categories, in the order they were added
     * @param attention verifications that are expired, rejected, still to do, under review, or expire within 30 days
     */
    record Seller(
            String id,
            String name,
            String type,
            @Nullable String tier,
            @Nullable String status,
            @Nullable String province,
            @Nullable String city,
            List<String> categoryIds,
            Instant createdAt,
            @Nullable Instant approvedAt,
            List<Check> attention) {

        public Seller {
            categoryIds = List.copyOf(categoryIds);
            attention = List.copyOf(attention);
        }
    }

    /** A {@code merchants.verifications} row. */
    record Check(
            String id,
            String checkType,
            @Nullable String registry,
            @Nullable String reference,
            String status,
            @Nullable Instant expiresAt) {}

    /** @param stripeAccountId the Connect account ({@code acct_…}), when onboarding created one */
    record SellerFile(Seller seller, @Nullable String stripeAccountId, List<Check> checks, List<Oversight> trail) {
        public SellerFile {
            checks = List.copyOf(checks);
            trail = List.copyOf(trail);
        }
    }

    /**
     * One staff action on the business ({@code merchants.oversight_actions}).
     *
     * @param action {@code suspended} | {@code reinstated} | {@code reverification_required} | {@code tier_changed}
     * @param detail codes and ids: {@code from}/{@code to} (tier), {@code verificationId}/{@code checkType}
     */
    record Oversight(
            String id,
            String action,
            String reason,
            Map<String, String> detail,
            String actorId,
            String actorRole,
            Instant at) {

        public Oversight {
            detail = Map.copyOf(detail);
        }
    }

    /** The oversight action an event names ({@code actionId}), for the business's notification email. */
    Optional<Oversight> action(String actionId);
}
