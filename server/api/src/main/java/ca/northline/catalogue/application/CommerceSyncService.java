package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceCatalogSource.ExternalProduct;
import ca.northline.catalogue.application.CommerceCatalogSource.Page;
import ca.northline.catalogue.application.CommerceLinkRepository.ProductLink;
import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.application.IntegrationRepository.SyncError;
import ca.northline.catalogue.application.IntegrationRepository.SyncResult;
import ca.northline.catalogue.application.IntegrationRepository.Webhooks;
import ca.northline.catalogue.application.SyncIntegrations.CommerceJobs;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The S-35 sync. A <b>full read</b> (after connect, "Sync now", every {@code COMMERCE_POLL_INTERVAL} without webhooks,
 * every {@code COMMERCE_RECONCILE_INTERVAL} with them) pages through the platform's active products, applies each one
 * ({@link CommerceImporter}) in its own transaction — so one product the rules refuse doesn't stop the rest, and its
 * message is listed in the Studio — and hides the listings whose product is gone (hide, never delete). A <b>webhook</b>
 * re-reads only the product it names. Price and stock always come from the platform: Northline never writes back.
 */
@Slf4j
@Service
class CommerceSyncService implements SyncIntegrations, CommerceJobs {

    static final int MAX_ERRORS = 50;
    static final Duration RECEIPTS_KEPT = Duration.ofDays(7);

    private final CommerceConnectionService connections;
    private final IntegrationRepository integrations;
    private final CommerceLinkRepository links;
    private final CommerceSources sources;
    private final CommerceCredentials credentials;
    private final CommerceImporter importer;
    private final ListingRepository listings;
    private final CommerceSettings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    CommerceSyncService(
            CommerceConnectionService connections,
            IntegrationRepository integrations,
            CommerceLinkRepository links,
            CommerceSources sources,
            CommerceCredentials credentials,
            CommerceImporter importer,
            ListingRepository listings,
            CommerceSettings settings,
            ApplicationEventPublisher events,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.connections = connections;
        this.integrations = integrations;
        this.links = links;
        this.sources = sources;
        this.credentials = credentials;
        this.importer = importer;
        this.listings = listings;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Studio ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<Connection> connections(String merchantId) {
        return inTx(() -> connections.connections(merchantId));
    }

    @Override
    public URI connect(String merchantId, String userId, CommerceProvider provider, @Nullable String shop) {
        return inTx(() -> connections.start(merchantId, userId, provider, shop));
    }

    @Override
    public Connection disconnect(String merchantId, CommerceProvider provider) {
        return inTx(() -> connections.disconnect(integrations
                .find(merchantId, provider)
                .orElseGet(() -> Connection.none(merchantId, provider, sources.available(provider)))));
    }

    @Override
    public Connection sync(String merchantId, CommerceProvider provider) {
        var connection = integrations
                .find(merchantId, provider)
                .filter(Connection::connected)
                .orElseThrow(() -> new Conflict("not_connected", "Connect this account first."));
        if (connection.needsReconnect()) {
            throw new Conflict("reconnect_required", "Reconnect this account to keep syncing.");
        }
        fullSync(connection.requiredId());
        return connections(merchantId).stream()
                .filter(c -> c.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new NotFound("integration", provider.code()));
    }

    // ── after connect, webhooks ────────────────────────────────────────────────────────────────────────────────────

    /** After connect: register webhooks (HTTPS api only), then import everything. */
    void connected(String integrationId) {
        var connection = integrations.byId(integrationId).orElse(null);
        if (connection == null || !connection.connected()) {
            return;
        }
        if (settings.webhooksReachable()) {
            var source = sources.get(connection.provider());
            var url = settings.webhookUrl(connection.provider());
            var subscribed = inTx(() ->
                    credentials.with(connection, c -> source.subscribe(c, url)).orElse(false));
            inTx(() -> {
                integrations.setWebhooks(integrationId, subscribed ? Webhooks.ACTIVE : Webhooks.FAILED);
                return true;
            });
        }
        fullSync(integrationId);
    }

    /** A webhook named this product (or nothing: then a full read). */
    void productChanged(String integrationId, @Nullable String externalId) {
        if (externalId == null) {
            fullSync(integrationId);
            return;
        }
        var connection = integrations.byId(integrationId).orElse(null);
        if (connection == null || !connection.connected()) {
            return;
        }
        var source = sources.get(connection.provider());
        var product = inTx(() -> credentials.with(connection, c -> source.product(c, externalId)))
                .orElse(null);
        if (product == null) {
            return; // needs a reconnect
        }
        if (product.isEmpty()) {
            productRemoved(integrationId, externalId);
            return;
        }
        try {
            inTx(() -> {
                var link = links.product(connection.merchantId(), connection.provider(), externalId)
                        .orElse(null);
                return importer.apply(connection, product.get(), link, linkedOffers(connection));
            });
        } catch (RuleViolation e) {
            log.info("Commerce product {} of {} not imported: {}", externalId, integrationId, e.getMessage());
        }
    }

    /** The platform deleted or archived the product: the listing is hidden (never deleted), the link remembers it. */
    void productRemoved(String integrationId, String externalId) {
        integrations
                .byId(integrationId)
                .ifPresent(connection ->
                        inTx(() -> links.product(connection.merchantId(), connection.provider(), externalId)
                                .filter(link -> link.removedAt() == null)
                                .map(link -> hide(link))
                                .orElse(false)));
    }

    // ── full read ──────────────────────────────────────────────────────────────────────────────────────────────────

    void fullSync(String integrationId) {
        var connection = integrations.byId(integrationId).orElse(null);
        if (connection == null || !connection.connected() || connection.needsReconnect()) {
            return;
        }
        inTx(() -> {
            integrations.markSyncing(integrationId);
            return true;
        });
        var source = sources.get(connection.provider());
        var known = inTx(() -> links.products(connection.merchantId(), connection.provider()));
        var linked = known.values().stream().map(ProductLink::offerId).collect(Collectors.toCollection(HashSet::new));
        var seen = new HashSet<String>();
        var errors = new ArrayList<SyncError>();
        int created = 0;
        int updated = 0;
        String cursor = null;
        try {
            do {
                var after = cursor;
                var page = inTx(() -> credentials.with(connection, c -> source.products(c, after)))
                        .orElse(null);
                if (page == null) {
                    return; // marked reconnect
                }
                for (var product : page.items()) {
                    seen.add(product.id());
                    switch (applyOne(connection, product, known.get(product.id()), linked, errors)) {
                        case CREATED -> created++;
                        case UPDATED -> updated++;
                        case UNCHANGED, SKIPPED -> {}
                    }
                }
                cursor = next(page);
            } while (cursor != null);
        } catch (RuntimeException e) {
            log.warn(
                    "Commerce sync {} ({}) failed: {}",
                    integrationId,
                    connection.provider().code(),
                    e.toString());
            inTx(() -> {
                integrations.recordFailure(
                        integrationId, Objects.requireNonNullElse(e.getMessage(), "failed"), clock.instant());
                return true;
            });
            return;
        }
        var hidden = 0;
        for (var link : known.values()) {
            if (!seen.contains(link.externalId())
                    && link.removedAt() == null
                    && Boolean.TRUE.equals(inTx(() -> hide(link)))) {
                hidden++;
            }
        }
        var result = new SyncResult(created, updated, hidden, errors, clock.instant());
        inTx(() -> {
            integrations.recordSync(integrationId, result);
            return true;
        });
        log.info(
                "Commerce sync {} ({}): {} created, {} updated, {} hidden, {} not imported",
                integrationId,
                connection.provider().code(),
                created,
                updated,
                hidden,
                errors.size());
    }

    private CommerceImporter.Result applyOne(
            Connection connection,
            ExternalProduct product,
            @Nullable ProductLink link,
            Set<String> linked,
            List<SyncError> errors) {
        try {
            var result = inTx(() -> importer.apply(connection, product, link, linked));
            if (result == CommerceImporter.Result.CREATED || link == null) {
                inTx(() -> links.product(connection.merchantId(), connection.provider(), product.id()))
                        .ifPresent(l -> linked.add(l.offerId()));
            }
            return result;
        } catch (RuleViolation | Conflict e) {
            if (errors.size() < MAX_ERRORS) {
                errors.add(
                        new SyncError(product.id(), product.title(), Objects.requireNonNullElse(e.getMessage(), "")));
            }
            return CommerceImporter.Result.SKIPPED;
        }
    }

    private static @Nullable String next(Page page) {
        var next = page.next();
        return next == null || next.isBlank() ? null : next;
    }

    /** Hides the listing (publishing {@code listing.hidden} when customers could see it) and marks the link. */
    private boolean hide(ProductLink link) {
        links.markRemoved(link.merchantId(), link.provider(), link.externalId(), clock.instant());
        var listing = listings.product(link.merchantId(), link.offerId()).orElse(null);
        if (listing == null) {
            return false;
        }
        var event = listing.hide(clock.instant());
        listings.save(listing);
        event.ifPresent(events::publishEvent);
        return true;
    }

    private Set<String> linkedOffers(Connection connection) {
        return links.products(connection.merchantId(), connection.provider()).values().stream()
                .map(ProductLink::offerId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    // ── scheduler ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int syncDue() {
        var now = clock.instant();
        var pollBefore = now.minus(settings.pollInterval());
        var reconcileBefore = now.minus(settings.reconcileInterval());
        var ran = 0;
        for (var id : integrations.due(pollBefore, reconcileBefore, 50)) {
            if (Boolean.TRUE.equals(inTx(() -> integrations.claim(id, pollBefore, reconcileBefore, now)))) {
                fullSync(id);
                ran++;
            }
        }
        return ran;
    }

    @Override
    public int purge() {
        var now = clock.instant();
        return Objects.requireNonNull(inTx(() -> integrations.purge(now, now.minus(RECEIPTS_KEPT))));
    }

    private <T> T inTx(Supplier<T> work) {
        return Objects.requireNonNull(tx.execute(_ -> work.get()));
    }
}
