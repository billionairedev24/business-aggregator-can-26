package ca.northline.auth.application;

import java.util.Locale;

/** Who a limit counts against. */
public enum LimitScope {
    /**
     * The account as typed (normalised email or E.164 mobile) or, once signed in, the user id. Unknown identifiers are
     * limited exactly like known ones, so a 429 never tells whether an account exists.
     */
    ACCOUNT,
    /** The client IP (behind trusted proxies only, see {@code northline.auth.trusted-proxies}). */
    IP,
    /** The auth server's HTTP session (the browser's sign-in or registration flow). */
    SESSION,
    /**
     * S-104: everyone together — a platform-wide budget, e.g. for texts (SMS pumping: many numbers from many addresses
     * stay under every per-account and per-IP limit, and each text costs money). Reaching it stops that action for
     * everybody until the lockout ends, so it is set well above normal traffic and its lockout logged as an error.
     */
    PLATFORM;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
