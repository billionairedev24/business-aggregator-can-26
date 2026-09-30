package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceCatalogSource.Change;
import ca.northline.catalogue.application.CommerceCatalogSource.WebhookRequest;
import ca.northline.catalogue.application.CommerceSyncEvents.CommerceProductChanged;
import ca.northline.catalogue.application.CommerceSyncEvents.CommerceProductRemoved;
import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.application.SyncIntegrations.ReceiveCommerceWebhook;
import ca.northline.catalogue.domain.CommerceProvider;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform webhooks (S-35): the adapter verifies the HMAC signature (Shopify: body with the app secret; Square: the
 * notification URL + body with the subscription's signature key; Lightspeed: body with the app secret); a delivery id
 * seen before is acknowledged and ignored; the work is queued as internal events in this transaction (outbox) and runs
 * after commit, so the platform gets its 200 at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CommerceWebhookService implements ReceiveCommerceWebhook {

    private final CommerceSources sources;
    private final IntegrationRepository integrations;
    private final CommerceLinkRepository links;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public Receipt receive(CommerceProvider provider, WebhookRequest request) {
        var delivery = sources.get(provider).verify(request);
        var now = clock.instant();
        if (!integrations.firstDelivery(provider, delivery.deliveryId(), now)) {
            return Receipt.DUPLICATE;
        }
        for (var connection : integrations.byAccount(provider, delivery.accountId())) {
            for (var change : delivery.changes()) {
                apply(connection, change, now);
            }
        }
        return Receipt.ACCEPTED;
    }

    private void apply(Connection connection, Change change, java.time.Instant now) {
        var id = connection.id();
        if (id == null) {
            return;
        }
        if (change instanceof Change.Redact) {
            var forgotten = links.deleteAll(connection.merchantId(), connection.provider());
            if (connection.connected()) {
                integrations.disconnect(id, now);
            }
            log.info("Commerce integration {}: shop redacted, {} product link(s) forgotten", id, forgotten);
            return;
        }
        if (!connection.connected()) {
            return;
        }
        switch (change) {
            case Change.ProductChanged(var externalId) ->
                events.publishEvent(new CommerceProductChanged(id, externalId));
            case Change.ProductRemoved(var externalId) ->
                events.publishEvent(new CommerceProductRemoved(id, externalId));
            case Change.StockChanged(var stockRef) ->
                links.productOfStock(connection.merchantId(), connection.provider(), stockRef)
                        .ifPresent(product -> events.publishEvent(new CommerceProductChanged(id, product)));
            case Change.CatalogChanged() -> events.publishEvent(new CommerceProductChanged(id, null));
            case Change.Uninstalled() -> {
                integrations.disconnect(id, now);
                log.info(
                        "Commerce integration {} ({}) was removed on the platform",
                        id,
                        connection.provider().code());
            }
            case Change.Redact() -> {}
        }
    }
}
