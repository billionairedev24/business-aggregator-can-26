package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import java.util.List;

/** Outbound port: the Shopify / Square / Lightspeed catalogues a merchant connects (a local fake in dev and tests). */
public interface CommerceSync {

    /** An item in the external catalogue, keyed by SKU. */
    record ExternalItem(String sku, long priceCents, int stock) {}

    /** Authorises the connection; returns the external account label (e.g. the shop domain). */
    String connect(String merchantId, CommerceProvider provider);

    List<ExternalItem> fetchInventory(String merchantId, CommerceProvider provider);
}
