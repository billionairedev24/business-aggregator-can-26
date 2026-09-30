package ca.northline.hire.application;

import ca.northline.availability.api.ProviderSlots;
import ca.northline.availability.api.ServiceAreas;
import ca.northline.catalogue.api.ServiceOffers;
import ca.northline.catalogue.api.ServiceOffers.Offer;
import ca.northline.hire.application.ProviderPages.CategoryRef;
import ca.northline.hire.application.ProviderPages.ListProviderReviews;
import ca.northline.hire.application.ProviderPages.ProviderPage;
import ca.northline.hire.application.ProviderPages.Service;
import ca.northline.hire.application.ProviderPages.ViewProvider;
import ca.northline.hire.domain.ServiceKind;
import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.api.PublicProviders;
import ca.northline.shared.NotFound;
import ca.northline.trust.api.PublicReviews;
import ca.northline.trust.api.PublicReviews.ReviewPage;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.RatingQuery;
import ca.northline.region.api.TaxRates;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/** The public provider page's facts, composed from merchants, catalogue, availability and trust. */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ProviderPageService implements ViewProvider, ListProviderReviews {

    /** The storefront spec's "three most recent verified reviews". */
    static final int FIRST_REVIEWS = 3;

    static final int MAX_REVIEW_PAGE = 20;

    private final PublicProviders providers;
    private final ServiceOffers offers;
    private final CategorySource categories;
    private final ServiceAreas areas;
    private final ProviderSlots slots;
    private final RatingQuery ratings;
    private final QualityQuery quality;
    private final PublicReviews reviews;
    private final TaxRates taxRates;
    private final HireProperties region;

    @Override
    public ProviderPage provider(String slug, String lang) {
        var p = find(slug, lang);
        var offered = offers.ofMerchant(p.merchantId(), lang);
        var leafIds = offered.stream()
                .map(Offer::categoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var leaves = categories.byIds(leafIds).stream()
                .collect(Collectors.toMap(CategorySource.Category::id, Function.identity()));
        var main = offered.stream()
                .map(Offer::categoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .map(leaves::get)
                .orElse(null);
        var kind = main == null ? ServiceKind.VISIT : ServiceKind.of(main.id(), null);
        var rating = ratings.summary(p.merchantId());
        var score = quality.latest(p.merchantId());
        var shortest = offered.stream().mapToInt(Offer::durationMin).min().orElse(60);
        return new ProviderPage(
                p.merchantId(),
                p.slug(),
                p.displayName(),
                p.tier(),
                p.city(),
                p.since(),
                p.verifiedFacts(),
                rating.average(),
                rating.count(),
                QualityFigures.of(score, "on_time"),
                QualityFigures.of(score, "disputes"),
                QualityFigures.of(score, "rebook"),
                kind,
                main == null ? null : new CategoryRef(main.id(), slug(main.id()), main.names()),
                main != null && ServiceKind.vehicle(main.id()),
                kind.quoteable(),
                offered.stream().map(ProviderPageService::service).toList(),
                areas.zones(List.of(p.merchantId())).getOrDefault(p.merchantId(), List.of()),
                offered.isEmpty() ? null : slots.next(p.merchantId(), shortest).orElse(null),
                taxRates.bpsFor(Objects.requireNonNullElse(p.province(), region.defaultProvince())),
                reviews.newest(p.merchantId(), FIRST_REVIEWS, 0));
    }

    @Override
    public ReviewPage reviews(String slug, int limit, int offset) {
        var p = find(slug, "en");
        return reviews.newest(p.merchantId(), Math.clamp(limit, 1, MAX_REVIEW_PAGE), Math.max(0, offset));
    }

    private PublicProviders.Provider find(String slug, String lang) {
        return providers.bySlug(slug, lang).orElseThrow(() -> new NotFound("provider", slug));
    }

    private static Service service(Offer o) {
        var category = o.categoryId();
        return new Service(
                o.serviceId(),
                o.name(),
                o.included(),
                o.pricingMode(),
                o.quoteOnly() ? null : o.priceCents(),
                o.durationMin(),
                o.instantBook(),
                category == null ? null : slug(category),
                category == null ? ServiceKind.VISIT : ServiceKind.of(category, null));
    }

    private static String slug(String categoryId) {
        return categoryId.substring(categoryId.lastIndexOf('.') + 1);
    }
}
