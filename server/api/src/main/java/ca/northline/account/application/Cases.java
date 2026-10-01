package ca.northline.account.application;

import ca.northline.account.application.Problems.Card;
import ca.northline.account.application.ViewActivity.Item;
import ca.northline.messaging.api.CustomerCaseDesk.CaseThread;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Help &amp; cases (design 06 `at.help`, S-60): the customer's refund cases and disputes, and one case's timeline. */
public final class Cases {
    private Cases() {}

    /**
     * @param subject the order, food order or booking it is about (an Orders &amp; bookings row), when still listed
     * @param state payments' state ({@code seller_review | agent_review | approved | denied | paid}, or a dispute's)
     */
    public record Row(
            String id,
            String number,
            String kind,
            String state,
            boolean open,
            String what,
            long amountCents,
            long taxCents,
            String merchantName,
            Instant openedAt,
            @Nullable Instant respondBy,
            @Nullable String outcome,
            @Nullable Long settledCents,
            @Nullable Item subject) {}

    /**
     * One step of the timeline (consumer app `refund`: Submitted → Seller reviews → Northline decides → Refund issued).
     *
     * @param key {@code submitted | seller | northline | refund}
     * @param state {@code done | current | todo | skipped | denied}
     * @param at when it happened, or until when it runs (the business's review)
     */
    public record Step(String key, String state, @Nullable Instant at) {}

    /** @param thread the case in Northline's queue opened with it (code, notes), when there is one */
    public record Detail(
            Row row,
            List<Step> steps,
            @Nullable Card card,
            @Nullable CaseThread thread) {
        public Detail {
            steps = List.copyOf(steps);
        }
    }

    public interface ViewCases {
        List<Row> cases(String userId);

        Detail detail(String userId, String caseId);

        Detail addNote(String userId, String caseId, String body, List<String> attachmentIds);
    }
}
