package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CatalogRecord;
import java.util.Optional;

/** Outbound port for {@code catalogue.catalog_products} (shared and seller-owned records). */
public interface CatalogRecords {

    Optional<CatalogRecord> byGtin(String gtin);

    Optional<CatalogRecord> byId(String id);

    /** Next display code, e.g. {@code NL-P-88201}. */
    String nextRef();

    void insert(CatalogRecord record);

    void update(CatalogRecord record);
}
