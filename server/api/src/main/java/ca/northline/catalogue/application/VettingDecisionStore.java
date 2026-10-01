package ca.northline.catalogue.application;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-92): which listings the vetting queue shows, and the reviewers' decisions. */
public interface VettingDecisionStore {

    /**
     * Ids of flagged pending listings in scope (oldest submission first), of {@code alsoIds}, and of listings decided
     * since {@code decidedSince} in scope.
     */
    List<String> queueIds(MerchantScope scope, Collection<String> alsoIds, Instant decidedSince, int limit);

    long autoApproved(MerchantScope scope, Instant since);

    void insert(Decision decision);

    /** The latest decision on a listing. */
    Optional<Decision> latest(String listingId);

    record Decision(
            String id,
            String listingId,
            String kind,
            String merchantId,
            String decision,
            List<String> reasons,
            List<String> flags,
            @Nullable String note,
            String decidedBy,
            String role,
            Instant decidedAt) {
        public Decision {
            reasons = List.copyOf(reasons);
            flags = List.copyOf(flags);
        }
    }
}
