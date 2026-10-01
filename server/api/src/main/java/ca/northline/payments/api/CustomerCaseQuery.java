package ca.northline.payments.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A customer's own refund cases ({@code RF-…}) and disputes ({@code DS-…}), newest first — the account area's
 * "Refunds &amp; cases" (S-58) and "Help &amp; cases" (S-60). Read-only; opening one is {@link CustomerCases}.
 */
public interface CustomerCaseQuery {

    /**
     * @param kind {@code refund | dispute}
     * @param state refund: {@code seller_review | agent_review | approved | denied | paid}; dispute: {@code open |
     *     seller_replied | agent | decided | appealed}
     * @param amountCents what is asked back (refund) or disputed, before tax
     * @param refType what the money paid for: {@code order_line | booking | food_order}
     * @param refId that line, booking or food order
     * @param respondBy until when the business may answer (seller review), else null
     * @param outcome what came of it: {@code refund | credit} (paid or approved), {@code denied}, a dispute's decision
     *     ({@code full_refund | partial | release | goodwill}), or null while open
     * @param settledCents the money given back (paid or approved refund, a dispute's refund), else null
     */
    record CaseSummary(
            String id,
            String kind,
            String number,
            String state,
            String merchantId,
            String what,
            long amountCents,
            long taxCents,
            @Nullable String refType,
            @Nullable String refId,
            Instant openedAt,
            @Nullable Instant respondBy,
            @Nullable Instant decidedAt,
            @Nullable Instant paidAt,
            @Nullable String outcome,
            @Nullable Long settledCents) {

        /** Still being looked at (nothing decided yet). */
        public boolean open() {
            return switch (state) {
                case "requested", "seller_review", "agent_review", "open", "seller_replied", "agent", "appealed" ->
                    true;
                default -> false;
            };
        }
    }

    /** The customer's cases, newest first (at most {@code limit}). */
    List<CaseSummary> cases(String customerId, int limit);
}
