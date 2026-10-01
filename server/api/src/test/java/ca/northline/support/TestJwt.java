package ca.northline.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import ca.northline.config.NorthlineJwtConverter;
import ca.northline.shared.security.StaffRole;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * MockMvc {@code .with(...)} helpers producing the same authorities as a real northline-auth token (via
 * {@link NorthlineJwtConverter}). Merchant membership itself comes from the database ({@link TestData}).
 */
public final class TestJwt {

    private TestJwt() {}

    /** Studio user: {@code scope=openid profile merchant}, {@code acr=mfa}. Combine with a membership row. */
    public static JwtRequestPostProcessor member(String userId) {
        return token(userId, "openid profile merchant", "mfa");
    }

    /** Studio user who signed in with a single factor ({@code acr} absent) — merchant endpoints answer 403. */
    public static JwtRequestPostProcessor memberWithoutMfa(String userId) {
        return token(userId, "openid profile merchant", null);
    }

    /** Plain customer token ({@code scope=openid profile}). */
    public static JwtRequestPostProcessor customer(String userId) {
        return token(userId, "openid profile", null);
    }

    /** A consumer who signed in with a passkey or authenticator ({@code acr=mfa}; S-51 checkout needs no step-up). */
    public static JwtRequestPostProcessor customerWithMfa(String userId) {
        return token(userId, "openid profile orders bookings", "mfa");
    }

    /**
     * Northline staff (platform console: role {@code staff} plus the console roles given — S-90, none = the console
     * opens only the profile and on-call screens — and {@code acr=mfa}).
     */
    public static JwtRequestPostProcessor staff(String userId, StaffRole... roles) {
        return staffToken(userId, "mfa", roles);
    }

    /** Staff who signed in with a single factor. */
    public static JwtRequestPostProcessor staffWithoutMfa(String userId, StaffRole... roles) {
        return staffToken(userId, null, roles);
    }

    /**
     * S-30: a partner client's token as northline-auth issues it ({@code sub = partner:<name>}, {@code roles: [partner]},
     * the businesses it is bound to in {@code merchants}, no {@code acr}).
     */
    public static JwtRequestPostProcessor partner(String clientId, String scope, String... merchants) {
        return jwt().jwt(j -> j.subject(clientId)
                        .claim("scope", scope)
                        .claim("roles", java.util.List.of("partner"))
                        .claim("merchants", java.util.List.of(merchants)))
                .authorities(NorthlineJwtConverter::authorities);
    }

    private static JwtRequestPostProcessor staffToken(String userId, String acr, StaffRole... roles) {
        var claim = new java.util.ArrayList<>(java.util.List.of(StaffRole.STAFF));
        java.util.Arrays.stream(roles).map(StaffRole::code).forEach(claim::add);
        return jwt().jwt(j -> {
                    j.subject(userId).claim("scope", "openid profile console").claim("roles", claim);
                    if (acr != null) {
                        j.claim("acr", acr);
                    }
                })
                .authorities(NorthlineJwtConverter::authorities);
    }

    private static JwtRequestPostProcessor token(String userId, String scope, String acr) {
        return jwt().jwt(j -> {
                    j.subject(userId).claim("scope", scope);
                    if (acr != null) {
                        j.claim("acr", acr);
                    }
                })
                .authorities(NorthlineJwtConverter::authorities);
    }
}
