package ca.northline.shared.security;

import ca.northline.shared.CodedEnum;
import org.springframework.security.access.AccessDeniedException;

/** 403 from {@link StaffAccess} (S-90); {@link #reason()} becomes the ProblemDetail {@code code}. */
public final class StaffAccessDenied extends AccessDeniedException {

    public static final String MFA_REQUIRED_MESSAGE = "Sign in with your second factor to do this.";
    public static final String NOT_STAFF_MESSAGE = "This is for Northline staff only.";
    public static final String ROLE_NOT_HELD_MESSAGE = "You don't hold that role.";
    public static final String SCREEN_MESSAGE = "Your role can't open this screen.";
    public static final String ACTION_MESSAGE = "Your role can't do this.";
    public static final String UNGUARDED_MESSAGE = "This endpoint is not available.";

    public enum Reason implements CodedEnum {
        /** Token lacks {@code acr=mfa}: sign in again with a passkey / authenticator. */
        MFA_REQUIRED,
        /** No {@code staff} platform role. */
        NOT_STAFF,
        /** {@code X-Console-Role} names a role the caller doesn't hold (or no role at all). */
        ROLE_NOT_HELD,
        /** None of the caller's (active) roles opens the screen or allows the action. */
        INSUFFICIENT_ROLE,
        /** Programming error: a console handler without {@link RequiresConsole}. */
        UNGUARDED_ENDPOINT
    }

    private final Reason reason;

    public StaffAccessDenied(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
