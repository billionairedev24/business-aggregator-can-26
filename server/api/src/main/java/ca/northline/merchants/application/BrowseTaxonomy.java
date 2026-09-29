package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;
import java.util.List;

/** The grouped, searchable category list of the Business step for one business type, with its limit. */
public interface BrowseTaxonomy {

    record TaxonomyView(MerchantType type, int limit, List<Taxonomy.Group> groups) {
        public TaxonomyView {
            groups = List.copyOf(groups);
        }
    }

    TaxonomyView forType(MerchantType type);
}
