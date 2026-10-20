package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceCatalogSource.Adjusted;
import ca.northline.catalogue.application.CommerceCatalogSource.Adjustment;
import ca.northline.catalogue.application.CommerceCatalogSource.Location;
import ca.northline.catalogue.application.CommerceStockPushStore.Push;
import ca.northline.catalogue.application.CommerceStockPushStore.State;
import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.application.StockSync.PushStock;
import ca.northline.catalogue.application.StockSync.RecordStockChanges;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.developer.api.AuditTrail;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-158 two-way stock. {@link #record} turns a Northline stock movement (a paid order's line, a restocked cancellation
 * or return) into one push per connected platform variant the SKU is linked to — in the caller's transaction, unique
 * per idempotency key, so an event delivered twice queues nothing new. {@link #pushDue} sends them: the location is the
 * one the merchant chose (a store with a single location uses it), the adapter applies the change idempotently where
 * the platform takes a key, and the outcome is stored — applied, a refusal the merchant must look at (a conflict), or
 * a passing failure tried again later with growing waits until {@code maxAttempts}. Nothing here blocks a sale: the
 * platform is told afterwards, and a refused change never undoes Northline's stock.
 */
@Slf4j
@Service
class CommerceStockService implements StockSync, RecordStockChanges, PushStock {

    /** A push is held by one replica this long while it is sent. */
    static final Duration LEASE = Duration.ofMinutes(5);

    static final Duration DONE_WINDOW = Duration.ofDays(7);
    static final int ATTENTION_SHOWN = 50;
    /** Conflicts a reconnect can cure; a location choice cures {@code choose_location}. */
    static final List<String> AFTER_RECONNECT = List.of("reconnect", "scope_missing", "disconnected");

    private final CommerceStockPushStore pushes;
    private final IntegrationRepository integrations;
    private final CommerceSources sources;
    private final CommerceCredentials credentials;
    private final ListingRepository listings;
    private final AuditTrail audit;
    private final StockPushSettings settings;
    private final Clock clock;

    CommerceStockService(
            CommerceStockPushStore pushes,
            IntegrationRepository integrations,
            CommerceSources sources,
            CommerceCredentials credentials,
            ListingRepository listings,
            AuditTrail audit,
            StockPushSettings settings,
            Clock clock) {
        this.pushes = pushes;
        this.integrations = integrations;
        this.sources = sources;
        this.credentials = credentials;
        this.listings = listings;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    // ── recording ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public int record(Change change) {
        if (change.delta() == 0) {
            return 0;
        }
        var sku = pushes.skuOf(change.merchantId(), change.offerId(), change.variantId())
                .orElse(null);
        if (sku == null) {
            return 0;
        }
        var targets = pushes.targets(change.merchantId(), change.offerId(), sku);
        var byIntegration = new HashMap<String, List<CommerceStockPushStore.Target>>();
        targets.forEach(t -> byIntegration
                .computeIfAbsent(t.integrationId(), _ -> new java.util.ArrayList<>())
                .add(t));
        var now = clock.instant();
        var queued = 0;
        for (var entry : byIntegration.entrySet()) {
            var first = entry.getValue().getFirst();
            // one Northline SKU linked to several variants of the same store: which one sold is unknown
            var ambiguous = entry.getValue().size() > 1;
            var push = new Push(
                    Ids.next(),
                    change.merchantId(),
                    first.provider(),
                    entry.getKey(),
                    change.offerId(),
                    sku,
                    first.externalVariantId(),
                    first.stockRef(),
                    change.delta(),
                    change.reason(),
                    change.sourceRef(),
                    change.key() + ":" + first.provider().code(),
                    ambiguous ? State.CONFLICT : State.PENDING,
                    ambiguous ? "ambiguous" : null,
                    0,
                    now,
                    ambiguous
                            ? "SKU " + sku + " is linked to " + entry.getValue().size() + " variants"
                            : null,
                    null,
                    null,
                    now,
                    null);
            if (pushes.insert(push)) {
                queued++;
            }
        }
        return queued;
    }

    // ── sending ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int pushDue() {
        var now = clock.instant();
        var due = pushes.claimDue(now, now.plus(LEASE), settings.batch());
        for (var push : due) {
            try {
                send(push);
            } catch (RuntimeException e) {
                retryOrFail(push, e);
            }
        }
        return due.size();
    }

    private void send(Push push) {
        var now = clock.instant();
        var connection = integrations.byId(push.integrationId()).orElse(null);
        if (connection == null || !connection.connected()) {
            pushes.conflict(push.id(), "disconnected", "the store is no longer connected", null, null, null, now);
            return;
        }
        if (connection.needsReconnect()) {
            pushes.conflict(push.id(), "reconnect", "access expired or was removed", null, null, null, now);
            return;
        }
        var source = sources.get(connection.provider());
        if (!source.canWriteStock(connection.scopes())) {
            pushes.conflict(push.id(), "scope_missing", "the connection may not change stock", null, null, null, now);
            return;
        }
        var location = location(connection, source).orElse(null);
        if (location == null) {
            if (integrations
                    .byId(push.integrationId())
                    .map(Connection::needsReconnect)
                    .orElse(true)) {
                pushes.conflict(push.id(), "reconnect", "access expired or was removed", null, null, null, now);
            } else {
                pushes.conflict(push.id(), "choose_location", "the store has several locations", null, null, null, now);
            }
            return;
        }
        var adjustment = new Adjustment(
                push.externalVariantId(),
                push.stockRef(),
                location,
                push.delta(),
                push.reason(),
                push.idempotencyKey(),
                push.sourceRef());
        var result =
                credentials.with(connection, c -> source.adjust(c, adjustment)).orElse(null);
        var at = clock.instant();
        switch (result) {
            case null ->
                pushes.conflict(push.id(), "reconnect", "access expired or was removed", location, null, null, at);
            case Adjusted.Applied(var after)
            when after != null && after < 0 ->
                pushes.conflict(
                        push.id(), "oversold", "the platform's stock went to " + after, location, after, at, at);
            case Adjusted.Applied(var after) -> pushes.complete(push.id(), location, after, at);
            case Adjusted.Refused(var conflict, var detail) ->
                pushes.conflict(push.id(), conflict, detail, location, null, null, at);
        }
    }

    /**
     * The chosen location; a store with a single location uses (and remembers) it; empty when there are several and
     * none is chosen, or the grant was refused.
     */
    private Optional<String> location(Connection connection, CommerceCatalogSource source) {
        if (connection.locationId() != null) {
            return Optional.of(connection.locationId());
        }
        var found = credentials.with(connection, source::locations).orElse(List.of());
        if (found.size() != 1) {
            return Optional.empty();
        }
        var only = found.getFirst();
        integrations.setLocation(connection.requiredId(), only.id(), only.name());
        return Optional.of(only.id());
    }

    private void retryOrFail(Push push, RuntimeException e) {
        var attempts = push.attempts() + 1;
        var error = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        var now = clock.instant();
        if (attempts >= settings.maxAttempts()) {
            log.warn(
                    "Stock push {} to {} failed for good after {} attempts: {}",
                    push.id(),
                    push.provider().code(),
                    attempts,
                    error);
            pushes.fail(push.id(), attempts, error, now);
            return;
        }
        log.info(
                "Stock push {} to {} failed (attempt {}), retrying: {}",
                push.id(),
                push.provider().code(),
                attempts,
                error);
        pushes.retryLater(push.id(), attempts, now.plus(settings.delayAfter(attempts)), error);
    }

    // ── the Studio ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Status status(String merchantId, CommerceProvider provider) {
        var connection = integrations.find(merchantId, provider).orElse(null);
        if (connection == null || !connection.connected()) {
            return new Status(provider, false, null, null, false, 0, 0, List.of());
        }
        var counts = pushes.counts(merchantId, provider, clock.instant().minus(DONE_WINDOW));
        var attention = pushes.attention(merchantId, provider, ATTENTION_SHOWN).stream()
                .map(this::view)
                .toList();
        return new Status(
                provider,
                true,
                connection.locationId(),
                connection.locationName(),
                sources.available(provider) && sources.get(provider).canWriteStock(connection.scopes()),
                counts.pending(),
                counts.done(),
                attention);
    }

    @Override
    public List<LocationView> locations(String merchantId, CommerceProvider provider) {
        var connection = connected(merchantId, provider);
        var source = sources.get(provider);
        return credentials
                .with(connection, source::locations)
                .orElseThrow(() -> new Conflict("integration_reconnect", "Reconnect the store first."))
                .stream()
                .map(l -> new LocationView(l.id(), l.name(), l.id().equals(connection.locationId())))
                .toList();
    }

    @Override
    @Transactional
    public Status chooseLocation(
            String merchantId, CommerceProvider provider, String locationId, String actorId, String role) {
        var connection = connected(merchantId, provider);
        var source = sources.get(provider);
        Location chosen = credentials
                .with(connection, source::locations)
                .orElseThrow(() -> new Conflict("integration_reconnect", "Reconnect the store first."))
                .stream()
                .filter(l -> l.id().equals(locationId))
                .findFirst()
                .orElseThrow(() -> RuleViolation.of("locationId", "option", LOCATION_UNKNOWN));
        var id = connection.requiredId();
        integrations.setLocation(id, chosen.id(), chosen.name());
        var now = clock.instant();
        pushes.requeueConflicts(id, List.of("choose_location"), now);
        audit.record(AuditTrail.Entry.of(merchantId, actorId, role, "integration.location_chosen", "integration", id)
                .withChange(
                        connection.locationId() == null ? null : Map.of("locationId", connection.locationId()),
                        Map.of("locationId", chosen.id(), "provider", provider.code())));
        return status(merchantId, provider);
    }

    @Override
    @Transactional
    public PushView retry(String merchantId, String pushId, String actorId) {
        var push = attentionPush(merchantId, pushId);
        if (push.doneAt() != null) {
            throw new Conflict("push_applied", PUSH_APPLIED);
        }
        pushes.requeue(push.id(), actorId, clock.instant());
        return view(pushes.find(merchantId, pushId).orElseThrow());
    }

    @Override
    @Transactional
    public PushView dismiss(String merchantId, String pushId, String actorId) {
        var push = attentionPush(merchantId, pushId);
        pushes.dismiss(push.id(), actorId, clock.instant());
        return view(pushes.find(merchantId, pushId).orElseThrow());
    }

    /** After a reconnect, the changes refused for want of access go again. */
    @Transactional
    void reconnected(String integrationId) {
        pushes.requeueConflicts(integrationId, AFTER_RECONNECT, clock.instant());
    }

    private Push attentionPush(String merchantId, String pushId) {
        var push = pushes.find(merchantId, pushId).orElseThrow(() -> new NotFound("stock change", pushId));
        if (push.state() != State.CONFLICT && push.state() != State.FAILED) {
            throw new Conflict("push_closed", PUSH_CLOSED);
        }
        return push;
    }

    private Connection connected(String merchantId, CommerceProvider provider) {
        var connection = integrations
                .find(merchantId, provider)
                .filter(Connection::connected)
                .orElseThrow(() -> new NotFound("integration", provider.code()));
        if (connection.needsReconnect()) {
            throw new Conflict("integration_reconnect", "Reconnect the store first.");
        }
        return connection;
    }

    private PushView view(Push p) {
        @Nullable
        String name = listings.product(p.merchantId(), p.offerId())
                .map(l -> l.getDetails().title())
                .orElse(null);
        return new PushView(
                p.id(),
                p.offerId(),
                name,
                p.sku(),
                p.delta(),
                p.reason(),
                p.sourceRef(),
                p.state().name().toLowerCase(java.util.Locale.ROOT),
                p.conflict(),
                p.lastError(),
                p.attempts(),
                p.platformAfter(),
                p.doneAt() != null,
                p.createdAt());
    }
}
