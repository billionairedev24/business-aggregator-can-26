package ca.northline.auth.application;

import ca.northline.auth.domain.Factor;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Token claims for a signed-in person. Access tokens: {@code roles} (platform roles), {@code merchants} (every
 * business the person belongs to), {@code acr=mfa} only when a second factor was used, and {@code amr}. ID tokens add the
 * OIDC profile claims the BFF turns into {@code GET /bff/session}.
 */
@Service
@RequiredArgsConstructor
public class UserClaimsService {

    public static final String MFA = "mfa";
    private static final ZoneId EDMONTON = ZoneId.of("America/Edmonton");

    private final UserAccounts accounts;

    /** The factors recorded on a session authentication ({@code FACTOR_*} authorities). */
    public static EnumSet<Factor> factorsOf(Authentication authentication) {
        var factors = EnumSet.noneOf(Factor.class);
        authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .flatMap(a -> Factor.fromAuthority(a).stream())
                .forEach(factors::add);
        return factors;
    }

    public Map<String, Object> accessTokenClaims(String userId, Collection<Factor> factors) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("roles", new ArrayList<>(accounts.platformRoles(userId)));
        claims.put("merchants", new ArrayList<>(accounts.merchantIds(userId)));
        putAuthenticationContext(claims, factors);
        return claims;
    }

    public Map<String, Object> idTokenClaims(String userId, Collection<Factor> factors) {
        var claims = new LinkedHashMap<String, Object>();
        accounts.findById(userId).ifPresent(u -> {
            claims.put("given_name", u.givenName());
            claims.put("family_name", u.familyName());
            claims.put("name", (u.givenName() + " " + u.familyName()).trim());
            if (u.email() != null) {
                claims.put("email", u.email());
            }
            if (u.phone() != null) {
                claims.put("phone_number", u.phone());
            }
            claims.put("locale", Objects.requireNonNullElse(u.locale(), "en-CA"));
            claims.put(
                    "member_since", u.createdAt().atZone(EDMONTON).toLocalDate().toString());
        });
        putAuthenticationContext(claims, factors);
        return claims;
    }

    private static void putAuthenticationContext(Map<String, Object> claims, Collection<Factor> factors) {
        if (Factor.isMfa(factors)) {
            claims.put("acr", MFA);
        }
        // A mutable ArrayList: the claims are stored with the authorization and read back on refresh, and Spring
        // Authorization Server's JSON allow-list refuses the JDK's immutable lists (S-19 found refreshes failing).
        List<String> amr =
                new ArrayList<>(factors.stream().map(Factor::amr).distinct().toList());
        if (!amr.isEmpty()) {
            claims.put("amr", amr);
        }
    }
}
