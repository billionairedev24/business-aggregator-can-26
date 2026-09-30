package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceCatalogSource.Grant;
import ca.northline.catalogue.application.CommerceSyncEvents.CommerceConnected;
import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.application.IntegrationRepository.OAuthRequest;
import ca.northline.catalogue.application.IntegrationRepository.SyncStatus;
import ca.northline.catalogue.application.IntegrationRepository.Webhooks;
import ca.northline.catalogue.application.SyncIntegrations.CompleteCommerceConnection;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.MerchantMemberships;
import ca.northline.shared.security.MerchantPermission;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connecting a platform (S-35): OAuth authorization code with a single-use, 256-bit state stored hashed and bound to
 * the owner who started it. The redirect URI is on the api host (docs/runbooks/edge.md), so the callback carries no
 * session: the state is what identifies the member and business, and the member must still be able to manage the
 * business when it comes back. The grant is sealed with the envelope key; the import then runs after commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CommerceConnectionService implements CompleteCommerceConnection {

    static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    static final String SHOP_REQUIRED = "Enter your Shopify store address (your-store.myshopify.com).";
    static final Pattern SHOP = Pattern.compile("^[a-z0-9][a-z0-9-]*\\.myshopify\\.com$");

    private final IntegrationRepository integrations;
    private final CommerceSources sources;
    private final CommerceCredentials credentials;
    private final CommerceSettings settings;
    private final MerchantMemberships memberships;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    List<Connection> connections(String merchantId) {
        var stored = integrations.all(merchantId);
        return Arrays.stream(CommerceProvider.values())
                .map(p -> stored.stream()
                        .filter(c -> c.provider() == p)
                        .findFirst()
                        .map(c -> c.withAvailable(sources.available(p)))
                        .orElseGet(() -> Connection.none(merchantId, p, sources.available(p))))
                .toList();
    }

    @Transactional
    URI start(String merchantId, String userId, CommerceProvider provider, @Nullable String shopInput) {
        if (!sources.available(provider)) {
            throw new Conflict("commerce_provider_unavailable", CommerceSources.UNAVAILABLE);
        }
        var shop = provider == CommerceProvider.SHOPIFY ? shop(shopInput) : null;
        var state = CommerceCredentials.random();
        var now = clock.instant();
        integrations.saveRequest(new OAuthRequest(
                CommerceCredentials.sha256(state), merchantId, userId, provider, shop, now, now.plus(REQUEST_TTL)));
        return sources.get(provider).authorizationUrl(state, settings.redirectUri(provider), shop);
    }

    /** {@code my-store}, {@code my-store.myshopify.com} or an admin URL → {@code my-store.myshopify.com}. */
    static String shop(@Nullable String input) {
        if (input == null || input.isBlank()) {
            throw RuleViolation.of("shop", "required", SHOP_REQUIRED);
        }
        var s = input.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        s = s.replaceFirst("/.*$", "");
        if (!s.contains(".")) {
            s = s + ".myshopify.com";
        }
        if (!SHOP.matcher(s).matches()) {
            throw RuleViolation.of("shop", "format", SHOP_REQUIRED);
        }
        return s;
    }

    @Override
    @Transactional
    public Completion complete(CommerceProvider provider, Map<String, String> params) {
        var state = params.get("state");
        var request = state == null
                ? null
                : integrations
                        .takeRequest(CommerceCredentials.sha256(state), clock.instant())
                        .orElse(null);
        if (request == null) {
            return new Completion(null, provider, Outcome.EXPIRED);
        }
        var merchantId = request.merchantId();
        if (request.provider() != provider) {
            return new Completion(merchantId, provider, Outcome.FAILED);
        }
        var role = memberships.roleOf(merchantId, request.userId());
        if (role.isEmpty() || !role.get().grants(MerchantPermission.MANAGE)) {
            log.warn("Commerce callback refused: {} can no longer manage {}", request.userId(), merchantId);
            return new Completion(merchantId, provider, Outcome.FAILED);
        }
        var error = params.get("error");
        if (error != null || params.get("code") == null) {
            var denied = "access_denied".equals(error);
            return new Completion(merchantId, provider, denied ? Outcome.DENIED : Outcome.FAILED);
        }
        var source = sources.get(provider);
        Grant grant;
        try {
            grant = source.exchange(params, settings.redirectUri(provider), request.shop());
        } catch (RuntimeException e) {
            log.warn("Commerce code exchange with {} failed: {}", provider.code(), e.getMessage());
            return new Completion(merchantId, provider, Outcome.FAILED);
        }
        var existing = integrations.find(merchantId, provider).orElse(null);
        var id = existing == null || existing.id() == null ? Ids.next() : existing.id();
        var now = clock.instant();
        var connection = (existing == null ? Connection.none(merchantId, provider, true) : existing)
                .withId(id)
                .withConnected(true)
                .withNeedsReconnect(false)
                .withExternalAccountId(grant.accountId())
                .withAccountLabel(grant.accountLabel())
                .withScopes(grant.scopes())
                .withConnectedAt(now)
                .withSyncStatus(SyncStatus.IMPORTING)
                .withWebhooks(Webhooks.NONE)
                .withLastError(null);
        integrations.saveGrant(connection, credentials.seal(grant.credentials(), id));
        events.publishEvent(new CommerceConnected(id));
        return new Completion(merchantId, provider, Outcome.CONNECTED);
    }

    /** Revokes the grant where the platform allows it, destroys the tokens; imported listings stay. */
    @Transactional
    Connection disconnect(Connection connection) {
        if (connection.id() != null && connection.connected()) {
            var source = sources.get(connection.provider());
            try {
                credentials.open(connection.id()).ifPresent(source::revoke);
            } catch (RuntimeException e) {
                log.warn(
                        "Commerce integration {}: revoking at {} failed (token destroyed anyway): {}",
                        connection.id(),
                        connection.provider().code(),
                        e.getMessage());
            }
            integrations.disconnect(connection.id(), clock.instant());
        }
        return integrations
                .find(connection.merchantId(), connection.provider())
                .map(c -> c.withAvailable(sources.available(c.provider())))
                .orElseGet(() -> Connection.none(
                        connection.merchantId(), connection.provider(), sources.available(connection.provider())));
    }
}
