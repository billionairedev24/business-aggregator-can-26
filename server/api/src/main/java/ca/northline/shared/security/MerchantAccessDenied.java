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
        UNGUARDED_ENDPOINT
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
