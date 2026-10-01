package ca.northline.messaging.application;

import ca.northline.shared.CodedEnum;
import org.jspecify.annotations.Nullable;

/**
 * S-132: a customer's "something's wrong" report sorted into a case category with a neutral summary for staff, for the
 * S-60 flow to open the right case. A suggestion only: it never opens a case, decides a refund or names an amount; the
 * route follows from the category by rule, not from the model.
 */
public interface TriageHelpRequest {

    enum Category implements CodedEnum {
        MISSING_ITEM,
        WRONG_ITEM,
        DAMAGED,
        NOT_AS_DESCRIBED,
        LATE,
        NOT_DELIVERED,
        SERVICE_NOT_DONE,
        SERVICE_QUALITY,
        NO_SHOW,
        BILLING,
        SAFETY,
        ACCOUNT,
        OTHER
    }

    /**
     * Which queue the S-60 flow should offer: a refund request on the order (goods), a dispute on the booking or order
     * (staff mediate), or a support case (Northline itself). Staff decide every outcome.
     */
    enum Route implements CodedEnum {
        REFUND_REQUEST,
        DISPUTE,
        SUPPORT
    }

    /** @param urgent safety reports: the S-60 flow shows Northline's urgent help and staff see them first */
    record Triage(Category category, Route route, boolean urgent, String summary, boolean aiAssisted, String model) {}

    /** @param refType {@code order} or {@code booking} when the report is about one, else null */
    Triage triage(String customerId, String text, @Nullable String refType);

    static Route route(Category category) {
        return switch (category) {
            case MISSING_ITEM, WRONG_ITEM, DAMAGED, NOT_AS_DESCRIBED, NOT_DELIVERED -> Route.REFUND_REQUEST;
            case LATE, SERVICE_NOT_DONE, SERVICE_QUALITY, NO_SHOW, BILLING -> Route.DISPUTE;
            case SAFETY, ACCOUNT, OTHER -> Route.SUPPORT;
        };
    }
}
