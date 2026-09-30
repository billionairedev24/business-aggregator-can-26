package ca.northline.bff.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * RFC 7662 token introspection at northline-auth ({@code northline.bff.introspection-uri}) with the BFF's client
 * credentials (S-19). Fails open: when the auth server can't answer, the token counts as active and the next check or
 * refresh decides.
 */
@Slf4j
@Component
class TokenIntrospection {

    private final BffProperties props;
    private final RestClient http;

    TokenIntrospection(BffProperties props) {
        this.props = props;
        var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    boolean active(OAuth2AuthorizedClient client) {
        var refresh = client.getRefreshToken();
        var form = new LinkedMultiValueMap<String, String>();
        form.add(
                "token",
                refresh != null
                        ? refresh.getTokenValue()
                        : client.getAccessToken().getTokenValue());
        form.add("token_type_hint", refresh != null ? "refresh_token" : "access_token");
        var registration = client.getClientRegistration();
        try {
            var answer = http.post()
                    .uri(props.introspectionUri())
                    .headers(h -> h.setBasicAuth(registration.getClientId(), registration.getClientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return answer == null || !Boolean.FALSE.equals(answer.get("active"));
        } catch (RuntimeException e) {
            log.warn("Token introspection failed (session kept until the next check): {}", e.getMessage());
            return true;
        }
    }
}
