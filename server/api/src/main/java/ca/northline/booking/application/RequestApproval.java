package ca.northline.booking.application;

import ca.northline.booking.domain.Approval;
import ca.northline.shared.security.CurrentMember;

/** "Request extra parts approval" mid-job; the customer approves in-app. Publishes {@code booking.scope_changed}. */
public interface RequestApproval {

    record Command(CurrentMember actor, String bookingId, String description, long amountCents) {}

    Approval request(Command command);
}
