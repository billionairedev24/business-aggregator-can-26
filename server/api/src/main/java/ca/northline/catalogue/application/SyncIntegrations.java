package ca.northline.catalogue.application;

import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.domain.CommerceProvider;
import java.util.List;

/** Shopify / Square / Lightspeed connections: connect, disconnect, and sync price & stock by SKU. */
public interface SyncIntegrations {

    /** One entry per provider, connected or not. */
    List<Connection> connections(String merchantId);

    Connection connect(String merchantId, CommerceProvider provider);

    Connection disconnect(String merchantId, CommerceProvider provider);

    Connection sync(String merchantId, CommerceProvider provider);
}
