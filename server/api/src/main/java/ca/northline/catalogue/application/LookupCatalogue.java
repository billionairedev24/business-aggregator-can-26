package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CatalogRecord;
import ca.northline.catalogue.domain.MediaAsset;
import java.util.List;
import java.util.Optional;

/**
 * "Look up": match a GTIN against the shared Northline catalogue. Throws a 422 for malformed GTINs. The images are
 * those {@code merchantId} may load: another business's unvetted photos are left out (S-123).
 */
public interface LookupCatalogue {

    record Match(CatalogRecord record, List<MediaAsset> images) {
        public Match {
            images = List.copyOf(images);
        }
    }

    Optional<Match> byGtin(String merchantId, String gtin);
}
