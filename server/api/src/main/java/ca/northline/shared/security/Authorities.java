package ca.northline.shared.security;

/** Granted-authority naming shared by the JWT converter, dev auth and {@link MerchantAccess}. */
public final class Authorities {
    public static final String SCOPE_PREFIX = "SCOPE_";
    public static final String ROLE_PREFIX = "ROLE_";
    public static final String MERCHANT_PREFIX = "MERCHANT_";
    /** Present when the token has {@code acr=mfa} (passkey or TOTP second factor). */
    public static final String MFA = "FACTOR_MFA";

    private Authorities() {}
}
