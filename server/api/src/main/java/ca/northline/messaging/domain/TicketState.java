package ca.northline.messaging.domain;

import ca.northline.shared.CodedEnum;

/** {@code messaging.tickets.state}. {@code WAITING} = Northline replied and waits on the business. */
public enum TicketState implements CodedEnum {
    NEW,
    IN_PROGRESS,
    WAITING,
    RESOLVED;

    public boolean open() {
        return this != RESOLVED;
    }
}
