package ca.northline.config;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantMemberships;
import ca.northline.shared.security.MerchantMemberships.Membership;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * LOCAL PROFILE ONLY. Authenticates {@code X-Dev-User: <identity.users id>} without the auth server by minting an
 * in-memory JWT with the same claims northline-auth would issue ({@code scope=openid profile merchant},
 * {@code merchants} from {@code merchants.merchant_members}, {@code acr=mfa} unless {@code X-Dev-Acr: none}).
 * A real {@code Authorization: Bearer} header always wins. Wired by {@link DevAuthConfig}.
 */
@Slf4j
@RequiredArgsConstructor
class DevAuthFilter extends OncePerRequestFilter {

    static final String USER_HEADER = "X-Dev-User";
    static final String ACR_HEADER = "X-Dev-Acr";

    private final MerchantMemberships memberships;
    private final NorthlineJwtConverter converter;
    private final Clock clock;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var userId = request.getHeader(USER_HEADER);
        if (userId != null && request.getHeader("Authorization") == null) {
            if (!Ids.isValid(userId)) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, USER_HEADER + " must be a ULID");
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(converter.convert(devToken(userId, request.getHeader(ACR_HEADER))));
            SecurityContextHolder.setContext(context);
            log.debug("DEV AUTH: {} {} as {}", request.getMethod(), request.getRequestURI(), userId);
        }
        chain.doFilter(request, response);
    }

    private Jwt devToken(String userId, @Nullable String acr) {
        var now = clock.instant();
        return Jwt.withTokenValue("dev-" + userId)
                .header("alg", "none")
                .issuer("urn:northline:dev-auth")
                .subject(userId)
                .claim("scope", "openid profile merchant")
                .claim(
                        "merchants",
                        memberships.membershipsOf(userId).stream()
                                .map(Membership::merchantId)
                                .toList())
                .claim("acr", acr == null ? "mfa" : acr)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)))
                .build();
    }
}
