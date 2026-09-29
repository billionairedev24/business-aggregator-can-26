package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TaxonomyService implements BrowseTaxonomy {

    private final Taxonomy taxonomy;

    @Override
    public TaxonomyView forType(MerchantType type) {
        return new TaxonomyView(type, type.categoryLimit(), taxonomy.groups(type.roots()));
    }
}
