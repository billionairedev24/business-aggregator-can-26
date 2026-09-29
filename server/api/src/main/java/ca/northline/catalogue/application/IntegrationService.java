package ca.northline.catalogue.application;

import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.shared.Conflict;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Or connect": Shopify, Square, Lightspeed. A sync pulls price and stock for every SKU that exists in both catalogues
 * (the external system never creates Northline listings — new products go through the editor or bulk upload so
 * they are vetted).
 */
@Service
@RequiredArgsConstructor
@Transactional
class IntegrationService implements SyncIntegrations {

    private final IntegrationRepository connections;
    private final CommerceSync commerce;
    private final ListingRepository listings;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Connection> connections(String merchantId) {
        var stored = connections.all(merchantId);
        return Arrays.stream(CommerceProvider.values())
                .map(p -> stored.stream()
                        .filter(c -> c.provider() == p)
                        .findFirst()
                        .orElse(new Connection(p, false, null, null, null, null)))
                .toList();
    }

    @Override
    public Connection connect(String merchantId, CommerceProvider provider) {
        var account = commerce.connect(merchantId, provider);
        var connection = new Connection(provider, true, account, clock.instant(), null, null);
        connections.upsert(merchantId, connection);
        return connection;
    }

    @Override
    public Connection disconnect(String merchantId, CommerceProvider provider) {
        var connection = new Connection(provider, false, null, null, null, null);
        connections.upsert(merchantId, connection);
        return connection;
    }

    @Override
    public Connection sync(String merchantId, CommerceProvider provider) {
        var current = connections
                .find(merchantId, provider)
                .filter(Connection::connected)
                .orElseThrow(() -> new Conflict("not_connected", "Connect this account first."));
        var now = clock.instant();
        var updated = 0;
        for (var item : commerce.fetchInventory(merchantId, provider)) {
            var listing = listings.bySku(merchantId, item.sku());
            if (listing.isEmpty()) {
                continue;
            }
            switch (listing.get()) {
                case ProductListing p -> {
                    p.restock(item.priceCents(), item.stock(), now);
                    listings.save(p);
                }
                case ServiceListing s -> {
                    s.reprice(item.priceCents(), now);
                    listings.save(s);
                }
            }
            updated++;
        }
        var synced = new Connection(provider, true, current.accountLabel(), current.connectedAt(), now, updated);
        connections.upsert(merchantId, synced);
        return synced;
    }
}
