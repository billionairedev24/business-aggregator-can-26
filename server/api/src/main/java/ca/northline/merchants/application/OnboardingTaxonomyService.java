package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class OnboardingTaxonomyService implements BrowseTaxonomy {

    private final Taxonomy taxonomy;
    private final CategoryLimitLookup categoryLimits;

    @Override
    public TaxonomyView forType(MerchantType type) {
        return new TaxonomyView(type, categoryLimits.limit(type), taxonomy.groups(type.roots()));
    }
}
