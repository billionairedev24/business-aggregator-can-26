package ca.northline.auth.config;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Registers (or updates) the OAuth clients from ARCHITECTURE.md § Identity on start-up: the confidential BFFs
 * ({@code consumer-bff}, {@code studio-bff}, {@code console-bff}: authorization code + PKCE, no consent) and the public
 * mobile apps ({@code mobile-consumer}, {@code courier-app}: PKCE, rotating refresh). Partner clients
 * (client credentials, private_key_jwt) are provisioned from the Console. Off under the {@code prod} profile.
 */
@Slf4j
@Component
@Profile("!prod")
@RequiredArgsConstructor
class ClientSeeder implements ApplicationRunner {

    private final RegisteredClientRepository repo;
    private final ClientProperties props;

    @Override
    public void run(ApplicationArguments args) {
        upsert(bff("studio-bff", props.studio(), "openid", "profile", "merchant"));
        upsert(bff("consumer-bff", props.consumer(), "openid", "profile", "orders", "bookings"));
        upsert(bff("console-bff", props.console(), "openid", "profile", "console"));
        upsert(mobile("mobile-consumer", "ca.northline.app:/oauth2redirect", "orders"));
        upsert(mobile("courier-app", "ca.northline.courier:/oauth2redirect", "deliveries"));
    }

    private void upsert(RegisteredClient.Builder client) {
        var built = client.build();
        var existing = repo.findByClientId(built.getClientId());
        repo.save(existing == null ? built : client.id(existing.getId()).build());
        log.debug("OAuth client registered: {}", built.getClientId());
    }

    private static RegisteredClient.Builder bff(String clientId, ClientProperties.Bff bff, String... scopes) {
        var builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientSecret(bff.secret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(bff.redirectUris()))
                .postLogoutRedirectUris(uris -> uris.addAll(bff.postLogoutRedirectUris()))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .refreshTokenTimeToLive(Duration.ofHours(12)) // = session idle limit
                        .reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                        .build());
        for (var scope : scopes) {
            builder.scope(scope);
        }
        return builder;
    }

    private static RegisteredClient.Builder mobile(String clientId, String redirect, String scope) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirect)
                .scope("openid")
                .scope("profile")
                .scope(scope)
                .scope("offline_access")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(10))
                        .reuseRefreshTokens(false)
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                        .build());
    }
}
