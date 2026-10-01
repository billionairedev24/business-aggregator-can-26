package ca.northline.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.security.Authorities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** S-128: in the cloud ({@code access: staff}) only Northline staff with a second factor read the developer docs. */
class DevDocsAccessFilterTest {

    private static final String DOCS = "https://api.example.test/mcp/docs";
    private static final DevDocsAccessFilter STAFF_ONLY =
            new DevDocsAccessFilter(new DevDocsProperties(true, DevDocsProperties.Access.STAFF, DOCS, 8, 24000));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void staffWithSecondFactor_andATokenForTheDocs_getsThrough() throws Exception {
        var chain = call(STAFF_ONLY, token(List.of(DOCS), true, true));

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void everyoneElseIsRefused() throws Exception {
        assertRefused(token(List.of(DOCS), false, true), 403, "insufficient_scope");
        assertRefused(token(List.of(DOCS), true, false), 403, "insufficient_user_authentication");
        assertRefused(token(List.of("https://api.example.test/mcp"), true, true), 401, "invalid_token");
        assertRefused(null, 401, "invalid_token");
    }

    @Test
    void open_letsAnyoneRead() throws Exception {
        var open = new DevDocsAccessFilter(new DevDocsProperties(true, DevDocsProperties.Access.OPEN, DOCS, 8, 24000));

        assertThat(call(open, null).getRequest()).isNotNull();
    }

    private static void assertRefused(JwtAuthenticationToken token, int status, String error) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
        if (token != null) {
            SecurityContextHolder.getContext().setAuthentication(token);
        }
        STAFF_ONLY.doFilter(new MockHttpServletRequest("POST", "/mcp/docs"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("WWW-Authenticate"))
                .contains("error=\"" + error + "\"")
                .contains(
                        "resource_metadata=\"https://api.example.test/.well-known/oauth-protected-resource/mcp/docs\"");
    }

    private static MockFilterChain call(DevDocsAccessFilter filter, JwtAuthenticationToken token) throws Exception {
        if (token != null) {
            SecurityContextHolder.getContext().setAuthentication(token);
        }
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("POST", "/mcp/docs"), new MockHttpServletResponse(), chain);
        return chain;
    }

    private static JwtAuthenticationToken token(List<String> audience, boolean staff, boolean mfa) {
        var jwt = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .subject("01J9ZD3V00000000000000STAF")
                .audience(audience)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("scope", "openid mcp")
                .build();
        var authorities = new ArrayList<GrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority(Authorities.SCOPE_PREFIX + "mcp"));
        if (staff) {
            authorities.add(new SimpleGrantedAuthority(Authorities.ROLE_PREFIX + "STAFF"));
        }
        if (mfa) {
            authorities.add(new SimpleGrantedAuthority(Authorities.MFA));
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
