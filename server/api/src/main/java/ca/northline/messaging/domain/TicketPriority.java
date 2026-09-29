package ca.northline.messaging.domain;

import ca.northline.shared.CodedEnum;

/** {@code messaging.tickets.priority}: urgent cases, the Master-tier priority queue, everyone else. */
public enum TicketPriority implements CodedEnum {
    NORMAL,
    PRIORITY,
    URGENT
}
