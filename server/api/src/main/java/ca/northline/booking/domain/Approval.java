package ca.northline.booking.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Instant;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/** Extra parts / scope change approved in-app by the customer mid-job ({@code booking.approvals}). */
public record Approval(
        String id,
        String bookingId,
        String description,
        long amountCents,
        State state,
        Instant requestedAt,
        String requestedBy,
        @Nullable Instant decidedAt) {

    public static final int DESCRIPTION_MAX = 160;
    /** Not in validation-rules.md; see docs/DECISIONS.md "Operations". */
    public static final String DESCRIPTION_REQUIRED = "Describe the extra parts or work.";

    public static final String DESCRIPTION_TOO_LONG = "At most 160 characters.";
    public static final String AMOUNT_REQUIRED = "Enter an amount.";

    public enum State implements CodedEnum {
        PENDING,
        APPROVED,
        DECLINED
    }

    static Approval request(String bookingId, String description, long amountCents, String actorId, Instant at) {
        var errors = new ArrayList<Violation>();
        var text = description.strip();
        if (text.isEmpty()) {
            errors.add(new Violation("description", "required", DESCRIPTION_REQUIRED));
        } else if (text.length() > DESCRIPTION_MAX) {
            errors.add(new Violation("description", "length", DESCRIPTION_TOO_LONG));
        }
        if (amountCents <= 0) {
            errors.add(new Violation("amountCents", "range", AMOUNT_REQUIRED));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return new Approval(Ids.next(), bookingId, text, amountCents, State.PENDING, at, actorId, null);
    }
}
