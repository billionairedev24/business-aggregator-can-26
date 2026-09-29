package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Gtin;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.shared.NavBadgeContributor;
import ca.northline.shared.RuleViolation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side: the listings table, categories, the GTIN lookup and the sidebar badge ("Products · 4"). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CatalogueBrowsingService implements BrowseListings, BrowseCategories, LookupCatalogue, NavBadgeContributor {

    static final int MAX_LIMIT = 500;

    private final ListingQueries queries;
    private final CategoryCatalog categories;
    private final CatalogRecords records;
    private final MediaRepository media;

    @Override
    public List<ListingSummary> list(String merchantId, @Nullable ListingKind kind, int limit, Locale locale) {
        return queries.list(merchantId, kind, Math.clamp(limit, 1, MAX_LIMIT), locale);
    }

    @Override
    public List<CategoryProfile> categories(String root, Locale locale) {
        return categories.all(root, locale);
    }

    @Override
    public Optional<Match> byGtin(String gtin) {
        Gtin.problem("gtin", gtin).ifPresent(v -> {
            throw new RuleViolation(List.of(v));
        });
        return records.byGtin(Gtin.normalize(gtin)).map(r -> new Match(r, media.findAll(r.imageIds())));
    }

    /** Sidebar badge for the Catalogue › Products / Services & prices / Listings item: the number of listings. */
    @Override
    public Map<String, String> badges(String merchantId, Locale locale) {
        var count = queries.count(merchantId);
        return count == 0 ? Map.of() : Map.of("products", Integer.toString(count));
    }
}
