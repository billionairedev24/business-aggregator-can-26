package ca.northline.messaging.domain;

import ca.northline.shared.security.MerchantRole;

/**
 * Which threads a team member sees in Messages. Owners see everything (customers and Northline support), technicians
 * only the customer threads of their own jobs, cooks every customer thread of the kitchen, bookkeepers none.
 */
public enum InboxScope {
    ALL,
    ASSIGNED,
    CUSTOMERS,
    NONE;

    public static InboxScope of(MerchantRole role) {
        return switch (role) {
            case OWNER -> ALL;
            case TECHNICIAN -> ASSIGNED;
            case COOK -> CUSTOMERS;
            case BOOKKEEPER -> NONE;
        };
    }
}
