package ca.northline.developer.application;

import ca.northline.developer.api.ApiKeyRevoked;
import ca.northline.developer.api.AuditTrail;
import ca.northline.developer.api.WebhookEndpointChanged;
import ca.northline.developer.application.DeveloperUseCases.Actor;
import ca.northline.developer.application.DeveloperUseCases.AddWebhookEndpoint;
import ca.northline.developer.application.DeveloperUseCases.IssueApiKey;
import ca.northline.developer.application.DeveloperUseCases.ListApiKeys;
import ca.northline.developer.application.DeveloperUseCases.ListWebhookEndpoints;
import ca.northline.developer.application.DeveloperUseCases.RemoveWebhookEndpoint;
import ca.northline.developer.application.DeveloperUseCases.RevokeApiKey;
import ca.northline.developer.application.DeveloperUseCases.RotateWebhookSecret;
import ca.northline.developer.application.DeveloperUseCases.ViewAuditLog;
import ca.northline.developer.domain.ApiKey;
import ca.northline.developer.domain.AuditRecord;
import ca.northline.developer.domain.DeveloperRules;
import ca.northline.developer.domain.Secrets;
import ca.northline.developer.domain.WebhookEndpoint;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API keys (secret shown once, SHA-256 stored), webhook endpoints (signing secret shown once, encrypted at rest) and the
 * audit log. Every change is audit-logged in the same transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DeveloperSettingsService
        implements ListApiKeys,
                IssueApiKey,
                RevokeApiKey,
                ListWebhookEndpoints,
                AddWebhookEndpoint,
                RotateWebhookSecret,
                RemoveWebhookEndpoint,
                ViewAuditLog {

    private final DeveloperStore store;
    private final WebhookSecretCipher cipher;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public List<ApiKey> list(String merchantId) {
        return store.activeKeys(merchantId);
    }

    @Override
    @Transactional
    public ApiKey.Issued issue(IssueApiKey.Command command) {
        var name = DeveloperRules.keyName(command.name());
        var scopes = DeveloperRules.scopes(command.scopes());
        var secret = Secrets.apiKey();
        var actor = command.actor();
        var key = new ApiKey(
                Ids.next(),
                actor.merchantId(),
                name,
                scopes,
                Secrets.shown(secret),
                ApiKey.DEFAULT_RATE_LIMIT,
                clock.instant(),
                null,
                null);
        store.insertKey(key, Secrets.sha256(secret), actor.userId());
        audit.record(entry(actor, "api_key.issued", "api_key", key.id()).withChange(null, Map.of("scopes", scopes)));
        return new ApiKey.Issued(key, secret);
    }

    @Override
    @Transactional
    public void revoke(Actor actor, String keyId) {
        var key = store.findKey(actor.merchantId(), keyId)
                .filter(ApiKey::active)
                .orElseThrow(() -> new NotFound("api key", keyId));
        var now = clock.instant();
        store.revokeKey(key.id(), now);
        audit.record(entry(actor, "api_key.revoked", "api_key", key.id()));
        events.publishEvent(new ApiKeyRevoked(Ids.next(), now, key.id(), actor.userId(), actor.merchantId()));
    }

    @Override
    public List<WebhookEndpoint> endpoints(String merchantId) {
        return store.endpoints(merchantId);
    }

    @Override
    @Transactional
    public WebhookEndpoint.WithSecret add(AddWebhookEndpoint.Command command) {
        var url = DeveloperRules.webhookUrl(command.url());
        var subscribed = DeveloperRules.events(command.events());
        var actor = command.actor();
        var secret = Secrets.webhookSecret();
        var endpoint =
                new WebhookEndpoint(Ids.next(), actor.merchantId(), url, subscribed, true, clock.instant(), null, null);
        store.insertEndpoint(endpoint, cipher.encrypt(secret), cipher.keyRef(), actor.userId());
        audit.record(entry(actor, "webhook.created", "webhook_endpoint", endpoint.id())
                .withChange(null, Map.of("events", subscribed)));
        publish(actor, endpoint.id(), "created");
        return new WebhookEndpoint.WithSecret(endpoint, secret);
    }

    @Override
    @Transactional
    public WebhookEndpoint.WithSecret rotate(Actor actor, String endpointId) {
        var endpoint = endpoint(actor, endpointId);
        var secret = Secrets.webhookSecret();
        store.replaceSecret(endpoint.id(), cipher.encrypt(secret), cipher.keyRef());
        audit.record(entry(actor, "webhook.secret_rotated", "webhook_endpoint", endpoint.id()));
        publish(actor, endpoint.id(), "secret_rotated");
        return new WebhookEndpoint.WithSecret(endpoint, secret);
    }

    @Override
    @Transactional
    public void remove(Actor actor, String endpointId) {
        var endpoint = endpoint(actor, endpointId);
        store.deleteEndpoint(endpoint.id());
        audit.record(entry(actor, "webhook.deleted", "webhook_endpoint", endpoint.id()));
        publish(actor, endpoint.id(), "deleted");
    }

    @Override
    public List<AuditRecord> recent(String merchantId) {
        return store.audit(merchantId, clock.instant().minus(Duration.ofDays(ViewAuditLog.DAYS)), ViewAuditLog.LIMIT);
    }

    private WebhookEndpoint endpoint(Actor actor, String endpointId) {
        return store.findEndpoint(actor.merchantId(), endpointId)
                .orElseThrow(() -> new NotFound("webhook endpoint", endpointId));
    }

    private void publish(Actor actor, String endpointId, String change) {
        events.publishEvent(new WebhookEndpointChanged(
                Ids.next(), clock.instant(), endpointId, actor.userId(), actor.merchantId(), change));
    }

    private static AuditTrail.Entry entry(Actor actor, String action, String targetType, String targetId) {
        return AuditTrail.Entry.of(actor.merchantId(), actor.userId(), actor.role(), action, targetType, targetId);
    }
}
