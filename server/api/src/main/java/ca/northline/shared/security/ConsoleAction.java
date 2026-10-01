package ca.northline.shared.security;

import ca.northline.shared.CodedEnum;

/**
 * Privileged things a console screen lets a role do (design 03 {@code ROLES.can} and the Data Table's {@code CAN}):
 * opening a screen is {@link ConsoleScreen}, changing something on it is one of these.
 */
public enum ConsoleAction implements CodedEnum {
    SUSPEND,
    DECIDE,
    REFUND,
    PROVINCE,
    PAYOUTS,
    KEYS,
    VERIFY,
    VET,
    DISPATCH,
    SUPPORT,
    /** S-83: write the support desk's reply macros (support leads, admins). */
    MACROS
}
