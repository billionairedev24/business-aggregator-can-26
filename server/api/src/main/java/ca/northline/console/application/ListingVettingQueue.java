package ca.northline.console.application;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — listing vetting (S-92, design 03 {@code vetting}): listings the automated checks flagged (S-39
 * re-vets included), listings with an open trust flag (S-133 AI screening) and dishes held by the S-67 price check, in
 * one queue across the catalogue, food and trust modules, each decided by a reviewer.
 */
public interface ListingVettingQueue {

    String DECISION_REQUIRED = "Choose approve or reject.";
    String NOT_IN_REVIEW = "This listing isn't waiting for a decision.";

    Queue queue(MerchantScope scope);

    /** Decides a listing ({@code kind = listing}: offer or service) or a held dish ({@code kind = dish}). */
    Item decide(Decision decision);

    record Decision(
            String kind,
            String id,
            boolean approve,
            List<String> reasons,
            @Nullable String note,
            String staffId,
            String role) {
        public Decision {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * @param autoApproved listings the automated checks approved in the last 7 days without a reviewer
     * @param flagged items waiting for a reviewer
     */
    record Queue(long autoApproved, long flagged, List<Item> items) {
        public Queue {
            items = List.copyOf(items);
        }
    }

    /**
     * One thing to vet.
     *
     * @param kind {@code product | service | dish}
     * @param province the business's province (two-letter code), when it recorded one
     * @param flags automated vetting flags ({@code price_outlier}, …); {@code price_check} for a held dish
     * @param revetReasons S-39: why an approved listing is back in vetting
     * @param deviationPct for a price flag: how far from the median, in whole percent
     * @param state {@code pending} while waiting for a reviewer, else {@code approved | rejected}
     */
    record Item(
            String id,
            String kind,
            String merchantId,
            String businessName,
            @Nullable String province,
            String name,
            @Nullable Long priceCents,
            @Nullable String category,
            @Nullable Long medianCents,
            @Nullable Integer deviationPct,
            @Nullable String regulator,
            List<String> flags,
            List<String> revetReasons,
            List<TrustFlag> trustFlags,
            String state,
            @Nullable Instant submittedAt,
            @Nullable Instant decidedAt,
            List<String> reasons,
            @Nullable String note) {
        public Item {
            flags = List.copyOf(flags);
            revetReasons = List.copyOf(revetReasons);
            trustFlags = List.copyOf(trustFlags);
            reasons = List.copyOf(reasons);
        }
    }

    /** An open S-133 flag on the listing: the model's (or the rule's) explanation. */
    record TrustFlag(String flagId, String rule, String source, String explanation, List<String> categories) {
        public TrustFlag {
            categories = List.copyOf(categories);
        }
    }
}
