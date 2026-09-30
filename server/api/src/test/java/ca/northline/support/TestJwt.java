package ca.northline.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import ca.northline.config.NorthlineJwtConverter;
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

    /** Northline staff (platform console: role {@code staff}, {@code acr=mfa}). */
    public static JwtRequestPostProcessor staff(String userId) {
        return staffToken(userId, "mfa");
    }

    /** Staff who signed in with a single factor. */
    public static JwtRequestPostProcessor staffWithoutMfa(String userId) {
        return staffToken(userId, null);
    }

    private static JwtRequestPostProcessor staffToken(String userId, String acr) {
        return jwt().jwt(j -> {
                    j.subject(userId)
                            .claim("scope", "openid profile console")
                            .claim("roles", java.util.List.of("staff"));
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
