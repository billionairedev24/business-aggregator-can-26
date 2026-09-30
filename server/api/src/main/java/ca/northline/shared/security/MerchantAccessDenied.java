package ca.northline.shared.security;

import ca.northline.shared.CodedEnum;
import org.springframework.security.access.AccessDeniedException;

/** 403 from {@link MerchantAccess}; {@link #reason()} becomes the ProblemDetail {@code code}. */
public final class MerchantAccessDenied extends AccessDeniedException {

    public enum Reason implements CodedEnum {
        /** Token lacks {@code acr=mfa}: the client should step up (passkey/TOTP) and retry. */
        MFA_REQUIRED,
        NOT_A_MEMBER,
        INSUFFICIENT_ROLE,
        /** Programming error: a {@code {merchantId}} handler without {@link RequiresMerchant}. */
        UNGUARDED_ENDPOINT,
        /** S-30: a partner token on an endpoint not open to partners, or without the scope it needs. */
        PARTNER_NOT_ALLOWED,
        /** S-30: a partner token for a business it isn't bound to (not in its {@code merchants} claim). */
        NOT_BOUND
    }

    private final Reason reason;

    public MerchantAccessDenied(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
