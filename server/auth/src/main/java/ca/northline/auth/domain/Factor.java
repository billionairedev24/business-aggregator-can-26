package ca.northline.auth.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * How a person proved who they are. Stored on the session's authentication as a {@code FACTOR_*} authority; the token
 * gets {@code acr=mfa} only when a second factor (passkey, TOTP, backup code) is present — never for SMS alone.
 */
public enum Factor {
    PASSKEY("passkey", "hwk", true),
    TOTP("totp", "otp", true),
    BACKUP_CODE("backup_code", "otp", true),
    PHONE_OTP("phone_otp", "sms", false),
    FEDERATED("federated", "fed", false);

    public static final String AUTHORITY_PREFIX = "FACTOR_";

    private final String code;
    private final String amr;
    private final boolean secondFactor;

    Factor(String code, String amr, boolean secondFactor) {
        this.code = code;
        this.amr = amr;
        this.secondFactor = secondFactor;
    }

    public String code() {
        return code;
    }

    /** RFC 8176 authentication method reference. */
    public String amr() {
        return amr;
    }

    public boolean isSecondFactor() {
        return secondFactor;
    }

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    public static Optional<Factor> fromAuthority(String authority) {
        return Arrays.stream(values())
                .filter(f -> f.authority().equals(authority))
                .findFirst();
    }

    /** {@code acr=mfa} when any second factor was used. */
    public static boolean isMfa(Collection<Factor> factors) {
        return factors.stream().anyMatch(Factor::isSecondFactor);
    }
}
