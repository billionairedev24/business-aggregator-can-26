package ca.northline.bff.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/** S-19: a refused token refresh during the relay ends the BFF session with 401; other failures pass through. */
class SessionRevocationCheckTest {

    private final OAuth2AuthorizedClientRepository clients = mock(OAuth2AuthorizedClientRepository.class);
    private final TokenIntrospection introspection = mock(TokenIntrospection.class);
    private final BffProperties props = new BffProperties(
            "studio",
            "http://api",
            "http://auth/oauth2/revoke",
            "/sign-in",
            "http://auth/oauth2/introspect",
            Duration.ofMinutes(1),
            "XSRF-TOKEN",
            false,
            "",
            false);
    private final SessionRevocationCheck filter =
            new SessionRevocationCheck(clients, introspection, props, Clock.systemUTC());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private OAuth2AuthenticationToken signedIn() {
        var user = new DefaultOAuth2User(List.of(), Map.of("sub", "u1"), "sub");
        var token = new OAuth2AuthenticationToken(user, List.of(), "studio");
        SecurityContextHolder.getContext().setAuthentication(token);
        return token;
    }

    @Test
    void invalidGrantDuringTheRelay_endsTheSessionWith401() throws Exception {
        var token = signedIn();
        var session = new MockHttpSession();
        session.setAttribute(SessionRevocationCheck.CHECKED_AT, Instant.now()); // not due for introspection
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (_, _) -> {
            throw new ServletException(
                    "Request processing failed",
                    new ClientAuthorizationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT), "studio"));
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"session_ended\"");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(clients).removeAuthorizedClient(eq("studio"), eq(token), any(), any());
    }

    @Test
    void otherFailures_areNotASignOut() {
        signedIn();
        var session = new MockHttpSession();
        session.setAttribute(SessionRevocationCheck.CHECKED_AT, Instant.now());
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setSession(session);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (_, _) -> {
                    throw new ServletException(
                            new ClientAuthorizationException(new OAuth2Error("invalid_token_response"), "studio"));
                }))
                .isInstanceOf(ServletException.class);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void introspectionRunsOncePerInterval() throws Exception {
        var token = signedIn();
        var client = mock(OAuth2AuthorizedClient.class);
        when(clients.loadAuthorizedClient(eq("studio"), eq(token), any())).thenReturn(client);
        when(introspection.active(client)).thenReturn(true);
        var session = new MockHttpSession();
        for (int i = 0; i < 3; i++) {
            var request = new MockHttpServletRequest("GET", "/api/v1/me");
            request.setSession(session);
            filter.doFilter(request, new MockHttpServletResponse(), (_, _) -> {});
        }
        verify(introspection, org.mockito.Mockito.times(1)).active(client);
    }
}
