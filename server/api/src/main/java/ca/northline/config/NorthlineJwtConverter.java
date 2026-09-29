package ca.northline.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
class NorthlineJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> auth = new ArrayList<>();
        for (String s : jwt.getClaimAsString("scope").split(" ")) auth.add(new SimpleGrantedAuthority("SCOPE_" + s));
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) roles.forEach(r -> auth.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())));
        List<String> merchants = jwt.getClaimAsStringList("merchants");
        if (merchants != null) merchants.forEach(m -> auth.add(new SimpleGrantedAuthority("MERCHANT_" + m)));
        if ("mfa".equals(jwt.getClaimAsString("acr"))) auth.add(new SimpleGrantedAuthority("FACTOR_MFA"));
        return new JwtAuthenticationToken(jwt, auth, jwt.getSubject());
    }
}
