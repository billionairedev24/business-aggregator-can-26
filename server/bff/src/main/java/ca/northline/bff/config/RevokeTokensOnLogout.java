package ca.northline.bff.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Sign-out: revokes the refresh token at northline-auth ({@code /oauth2/revoke}, RFC 7009) and forgets the authorized
 * client. Best effort — the session is invalidated either way. The Studio also ends the auth server's own session
 * ({@code POST /api/auth/sign-out}) so "Not you?" can't sign the same person back in silently.
 */
@Slf4j
@Component
class RevokeTokensOnLogout implements LogoutHandler {

    private final OAuth2AuthorizedClientRepository clients;
    private final BffProperties props;
    private final RestClient http = RestClient.create();

    RevokeTokensOnLogout(OAuth2AuthorizedClientRepository clients, BffProperties props) {
        this.clients = clients;
        this.props = props;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, @Nullable Authentication auth) {
        if (!(auth instanceof OAuth2AuthenticationToken token)) {
            return;
        }
        OAuth2AuthorizedClient client =
                clients.loadAuthorizedClient(token.getAuthorizedClientRegistrationId(), auth, request);
        if (client == null) {
            return;
        }
        var refresh = client.getRefreshToken();
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add(
                    "token",
                    refresh != null
                            ? refresh.getTokenValue()
                            : client.getAccessToken().getTokenValue());
            form.add("token_type_hint", refresh != null ? "refresh_token" : "access_token");
            var registration = client.getClientRegistration();
            http.post()
                    .uri(props.revocationUri())
                    .headers(h -> h.setBasicAuth(registration.getClientId(), registration.getClientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Token revocation failed (session is still invalidated): {}", e.getMessage());
        }
        clients.removeAuthorizedClient(token.getAuthorizedClientRegistrationId(), auth, request, response);
    }
}
