package ca.northline.shared.security;

import java.util.Set;

/**
 * The authenticated caller. Declare it as a controller method parameter and it is resolved from the access token
 * (claims sub, scope, roles, acr). Never trust ids from the request body for "who am I".
 *
 * @param userId ULID of {@code identity.users} (token {@code sub})
 * @param scopes OAuth scopes ({@code merchant}, {@code orders}, …)
 * @param roles platform roles ({@code staff}, …) — not merchant team roles
 * @param mfa whether the token was issued after a second factor ({@code acr=mfa})
 */
public record CurrentUser(String userId, Set<String> scopes, Set<String> roles, boolean mfa) {
    public CurrentUser {
        scopes = Set.copyOf(scopes);
        roles = Set.copyOf(roles);
    }
}
