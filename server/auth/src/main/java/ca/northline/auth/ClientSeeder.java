package ca.northline.auth;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.*;

/** Dev-only client registrations. Prod clients are provisioned via console + secrets manager. */
@Configuration
class ClientSeeder {
    @Bean
    ApplicationRunner seed(RegisteredClientRepository repo) {
        return args -> {
            if (repo.findByClientId("consumer-bff") != null) return;
            repo.save(bff(
                    "consumer-bff",
                    "http://localhost:8081/login/oauth2/code/northline",
                    "openid",
                    "profile",
                    "orders",
                    "bookings"));
            repo.save(bff(
                    "studio-bff",
                    "http://localhost:8082/login/oauth2/code/northline",
                    "openid",
                    "profile",
                    "merchant"));
            repo.save(RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId("mobile-consumer")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri("ca.northline.app:/oauth2redirect")
                    .scope("openid")
                    .scope("profile")
                    .scope("orders")
                    .scope("offline_access")
                    .clientSettings(
                            ClientSettings.builder().requireProofKey(true).build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(Duration.ofMinutes(10))
                            .reuseRefreshTokens(false)
                            .refreshTokenTimeToLive(Duration.ofDays(30))
                            .build())
                    .build());
        };
    }

    private RegisteredClient bff(String id, String redirect, String... scopes) {
        var b = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(id)
                .clientSecret("{noop}dev-" + id)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirect)
                .postLogoutRedirectUri(redirect.replace("/login/oauth2/code/northline", "/"))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .reuseRefreshTokens(false)
                        .build());
        for (String s : scopes) b.scope(s);
        return b.build();
    }
}
