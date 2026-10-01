package ca.northline.developer.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.developer.api.EmbedKeys;
import ca.northline.developer.application.DeveloperUseCases.Actor;
import ca.northline.developer.domain.DeveloperRules;
import ca.northline.developer.domain.PublishableKey;
import ca.northline.developer.domain.Secrets;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S-76 publishable keys: one active per business; rolling replaces it; changes are audit-logged. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class PublishableKeyService implements PublishableKeys, EmbedKeys {

    private final PublishableKeyStore store;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public Optional<PublishableKey> current(String merchantId) {
        return store.active(merchantId);
    }

    @Override
    @Transactional
    public PublishableKey roll(Actor actor, @Nullable List<String> allowedOrigins) {
        var previous = store.active(actor.merchantId());
        var origins = allowedOrigins != null
                ? DeveloperRules.origins(allowedOrigins)
                : previous.map(PublishableKey::allowedOrigins).orElse(List.of());
        var now = clock.instant();
        previous.ifPresent(p -> store.revoke(p.id(), now));
        var key = new PublishableKey(Ids.next(), actor.merchantId(), Secrets.publishableKey(), origins, now);
        store.insert(key, actor.userId());
        audit.record(AuditTrail.Entry.of(
                        actor.merchantId(),
                        actor.userId(),
                        actor.role(),
                        previous.isPresent() ? "publishable_key.rolled" : "publishable_key.issued",
                        "publishable_key",
                        key.id())
                .withChange(null, Map.of("allowedOrigins", origins)));
        return key;
    }

    @Override
    @Transactional
    public PublishableKey allowOrigins(Actor actor, List<String> allowedOrigins) {
        var current = store.active(actor.merchantId())
                .orElseThrow(() -> new NotFound("publishable key", actor.merchantId()));
        var origins = DeveloperRules.origins(allowedOrigins);
        store.origins(current.id(), origins);
        audit.record(AuditTrail.Entry.of(
                        actor.merchantId(),
                        actor.userId(),
                        actor.role(),
                        "publishable_key.origins_changed",
                        "publishable_key",
                        current.id())
                .withChange(
                        Map.of("allowedOrigins", current.allowedOrigins()), Map.of("allowedOrigins", origins)));
        return new PublishableKey(current.id(), current.merchantId(), current.key(), origins, current.createdAt());
    }

    @Override
    public Optional<EmbedKey> active(String publishableKey) {
        if (!publishableKey.startsWith(Secrets.PUBLISHABLE_PREFIX) || publishableKey.length() > 80) {
            return Optional.empty();
        }
        return store.byKey(publishableKey).map(k -> new EmbedKey(k.merchantId(), k.allowedOrigins()));
    }
}
