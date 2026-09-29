package ca.northline.messaging.domain;

import ca.northline.shared.CodedEnum;

/** {@code messaging.threads.kind}: a customer conversation, Northline writing to the business, or a helpdesk case. */
public enum ThreadKind implements CodedEnum {
    CUSTOMER,
    SUPPORT,
    CASE
}
