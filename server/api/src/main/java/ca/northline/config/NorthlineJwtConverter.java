package ca.northline.config;

import ca.northline.shared.security.Authorities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Maps northline-auth access tokens to authorities: {@code scope} → {@code SCOPE_x}, {@code roles} → {@code ROLE_X},
 * {@code merchants} → {@code MERCHANT_<id>} (informational — MerchantAccess checks the DB), {@code acr=mfa} →
 * {@code FACTOR_MFA}. Principal name = {@code sub} (identity.users id).
 */
@Component
public class NorthlineJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), Objects.requireNonNull(jwt.getSubject(), "sub"));
    }

    /** Public so tests can build identical authorities ({@code jwt().authorities(NorthlineJwtConverter::authorities)}). */
    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> auth = new ArrayList<>();
        var scope = jwt.getClaimAsString("scope");
        if (scope != null) {
            for (String s : scope.split(" ")) {
                if (!s.isBlank()) {
                    auth.add(new SimpleGrantedAuthority(Authorities.SCOPE_PREFIX + s));
                }
            }
        }
        var roles = Objects.requireNonNullElse(jwt.getClaimAsStringList("roles"), List.<String>of());
        roles.forEach(r -> auth.add(new SimpleGrantedAuthority(Authorities.ROLE_PREFIX + r.toUpperCase(Locale.ROOT))));
        var merchants = Objects.requireNonNullElse(jwt.getClaimAsStringList("merchants"), List.<String>of());
        merchants.forEach(m -> auth.add(new SimpleGrantedAuthority(Authorities.MERCHANT_PREFIX + m)));
        if ("mfa".equals(jwt.getClaimAsString("acr"))) {
            auth.add(new SimpleGrantedAuthority(Authorities.MFA));
        }
        return List.copyOf(auth);
    }
}
