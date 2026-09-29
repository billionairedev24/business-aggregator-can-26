package ca.northline.messaging.domain;

import ca.northline.shared.Conflict;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The state of a helpdesk case ({@code messaging.tickets}) as far as the business can change it: opening one and
 * replying. Agents move it through {@code in_progress → waiting → resolved} from the console.
 */
public record SupportCase(
        String id,
        int number,
        TicketState state,
        TicketPriority priority,
        @Nullable Instant slaDueAt) {

    /** Display code: {@code HD-4471}. */
    public static String code(int number) {
        return "HD-" + number;
    }

    public static SupportCase open(String id, int number, TicketPriority priority, Instant now) {
        return new SupportCase(id, number, TicketState.NEW, priority, SupportSla.dueAt(now, priority));
    }

    /**
     * The business wrote in the case. A resolved case stays closed (open a new one); a case waiting on the business
     * goes back to Northline with a fresh reply target.
     */
    public SupportCase replied(Instant now) {
        return switch (state) {
            case RESOLVED -> throw new Conflict("case_resolved", "This case is resolved. Open a new case.");
            case WAITING ->
                new SupportCase(id, number, TicketState.IN_PROGRESS, priority, SupportSla.dueAt(now, priority));
            case NEW, IN_PROGRESS -> this;
        };
    }
}
