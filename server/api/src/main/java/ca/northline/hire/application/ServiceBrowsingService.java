package ca.northline.hire.application;

import ca.northline.availability.api.ServiceAreas;
import ca.northline.availability.api.ServiceAreas.Place;
import ca.northline.catalogue.api.ServiceOffers;
import ca.northline.catalogue.api.ServiceOffers.Offer;
import ca.northline.hire.application.BrowseServices.Category;
import ca.northline.hire.application.BrowseServices.Group;
import ca.northline.hire.application.BrowseServices.Item;
import ca.northline.hire.application.BrowseServices.Job;
import ca.northline.hire.application.BrowseServices.Landing;
import ca.northline.hire.application.BrowseServices.ListCategories;
import ca.northline.hire.application.BrowseServices.ListProviders;
import ca.northline.hire.application.BrowseServices.ProviderCard;
import ca.northline.hire.application.BrowseServices.Providers;
import ca.northline.hire.application.BrowseServices.ViewCategory;
import ca.northline.hire.domain.ServiceKind;
import ca.northline.hire.domain.TrustRank;
import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.api.PublicProviders;
import ca.northline.shared.NotFound;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.RatingQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The services landing, a category and its providers, composed from the taxonomy (catalogue, through
 * {@code merchants.api.CategorySource}), live services (catalogue), published business pages (merchants), service areas
 * and live calendars (availability) and trust signals (trust). A category's providers are the published, active
 * service businesses with a live, approved service in it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ServiceBrowsingService implements ListCategories, ViewCategory, ListProviders {

    static final String ROOT = "service";
    static final int JOBS = 5;
    static final RatingQuery.RatingSummary NO_RATING = new RatingQuery.RatingSummary(0, 0);

    private final CategorySource categories;
    private final ServiceOffers offers;
    private final PublicProviders providers;
    private final ServiceAreas areas;
    private final NextFreeSlots nextSlots;
    private final RatingQuery ratings;
    private final QualityQuery quality;
    private final CategoryOrder order;
    private final RegionDefaults region;

    @Override
    public Landing landing(String lang) {
        var tree = taxonomy();
        var leafIds = tree.stream()
                .flatMap(g -> g.leaves().stream())
                .map(CategorySource.Category::id)
                .toList();
        var live = liveOffers(offers.inCategories(leafIds, lang), lang);
        var providersByCategory = live.stream()
                .collect(Collectors.groupingBy(
                        o -> Objects.requireNonNull(o.categoryId()),
                        Collectors.mapping(Offer::merchantId, Collectors.toSet())));
        var groups = tree.stream().map(g -> group(g, providersByCategory)).toList();
        int liveCategories = (int) groups.stream()
                .flatMap(g -> g.items().stream())
                .filter(i -> i.providers() > 0)
                .count();
        var merchantIds = merchants(live);
        return new Landing(liveCategories, merchantIds.size(), provinces(merchantIds, lang), groups);
    }

    @Override
    public Category category(String slug, String lang) {
        var found = find(slug);
        var leaf = found.leaf();
        var kind = ServiceKind.of(leaf.id(), null);
        var live = liveOffers(offers.inCategories(List.of(leaf.id()), lang), lang);
        var group = group(found.group(), Map.of(leaf.id(), merchants(live)));
        return new Category(
                leaf.id(),
                slug,
                leaf.names(),
                group,
                kind,
                ServiceKind.vehicle(leaf.id()),
                leaf.regulatedRegistry(),
                merchants(live).size(),
                provinces(merchants(live), lang),
                kind.quoteable(),
                jobs(live));
    }

    @Override
    public Providers providers(String slug, Place place, String lang) {
        var leaf = find(slug).leaf();
        var kind = ServiceKind.of(leaf.id(), null);
        var live = liveOffers(offers.inCategories(List.of(leaf.id()), lang), lang);
        var byMerchant = live.stream()
                .collect(Collectors.groupingBy(Offer::merchantId, LinkedHashMap::new, Collectors.toList()));
        var published = providers.published(byMerchant.keySet(), lang).stream()
                .collect(Collectors.toMap(PublicProviders.Provider::merchantId, Function.identity()));
        var zones = areas.zones(published.keySet());
        Set<String> covering = new HashSet<>(areas.covering(published.keySet(), place));
        if (!kind.comesToCustomer()) {
            // the customer goes to them: a shop or office in the customer's city counts too
            published.values().stream()
                    .filter(p -> p.city() != null && p.city().equalsIgnoreCase(place.city()))
                    .forEach(p -> covering.add(p.merchantId()));
        }
        var shown = published.values().stream()
                .filter(p -> covering.contains(p.merchantId()))
                .toList();
        // S-119: ratings and quality scores of every card in one query each, the next free slots memoised briefly
        var ids = shown.stream().map(PublicProviders.Provider::merchantId).toList();
        var rated = ratings.summaries(ids);
        var scores = quality.latestOf(ids);
        var cards = shown.stream()
                .map(p -> card(
                        p,
                        byMerchant.getOrDefault(p.merchantId(), List.of()),
                        zones.getOrDefault(p.merchantId(), List.of()),
                        rated.getOrDefault(p.merchantId(), NO_RATING),
                        Optional.ofNullable(scores.get(p.merchantId()))))
                .sorted(TrustRank.by(c -> new TrustRank.Signals(
                        c.tier(), c.onTimePct(), c.disputePct(), c.rebookPct(), c.rating(), c.name())))
                .toList();
        return new Providers(slug, kind, areas.zoneAt(place).orElse(null), place.city(), cards);
    }

    /** Where the live providers are (province codes from their businesses, sorted): the pages name no place of their own. */
    private List<String> provinces(Collection<String> merchantIds, String lang) {
        return providers.published(merchantIds, lang).stream()
                .map(PublicProviders.Provider::province)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private ProviderCard card(
            PublicProviders.Provider p,
            List<Offer> offered,
            List<String> zones,
            RatingQuery.RatingSummary rating,
            Optional<QualityQuery.QualityScore> score) {
        var priced = offered.stream().filter(o -> !o.quoteOnly()).toList();
        var cheapest = priced.stream().min(Comparator.comparingLong(o -> Objects.requireNonNull(o.priceCents())));
        var shortest = offered.stream().mapToInt(Offer::durationMin).min().orElse(60);
        return new ProviderCard(
                p.merchantId(),
                p.slug(),
                p.displayName(),
                p.tier(),
                p.brandColor(),
                p.tagline() != null ? p.tagline() : p.about(),
                rating.average(),
                rating.count(),
                QualityFigures.of(score, "on_time"),
                QualityFigures.of(score, "disputes"),
                QualityFigures.of(score, "rebook"),
                cheapest.map(Offer::priceCents).orElse(null),
                cheapest.map(Offer::pricingMode).orElse("quote"),
                offered.stream().anyMatch(Offer::instantBook),
                nextSlots.next(p.merchantId(), shortest).orElse(null),
                zones,
                region.zone(region.province(p.province())).getId());
    }

    /** Offers of published, active service businesses only. */
    private List<Offer> liveOffers(List<Offer> all, String lang) {
        var ids = all.stream().map(Offer::merchantId).collect(Collectors.toSet());
        var published = providers.published(ids, lang).stream()
                .map(PublicProviders.Provider::merchantId)
                .collect(Collectors.toSet());
        return all.stream().filter(o -> published.contains(o.merchantId())).toList();
    }

    private static Set<String> merchants(List<Offer> offers) {
        return offers.stream().map(Offer::merchantId).collect(Collectors.toSet());
    }

    /** One line per service name (case-insensitive), with the lowest price asked, most booked first. */
    static List<Job> jobs(List<Offer> offers) {
        var byName = new LinkedHashMap<String, List<Offer>>();
        offers.forEach(o -> byName.computeIfAbsent(o.name().strip().toLowerCase(Locale.ROOT), _ -> new ArrayList<>())
                .add(o));
        return byName.values().stream()
                .limit(JOBS)
                .map(same -> {
                    var first = same.getFirst();
                    var cheapest = same.stream()
                            .filter(o -> !o.quoteOnly())
                            .min(Comparator.comparingLong(o -> Objects.requireNonNull(o.priceCents())));
                    return new Job(
                            first.name().strip(),
                            first.included(),
                            cheapest.map(Offer::pricingMode).orElse("quote"),
                            cheapest.map(Offer::priceCents).orElse(null),
                            first.durationMin());
                })
                .toList();
    }

    private Group group(GroupNode g, Map<String, Set<String>> providersByCategory) {
        var items = g.leaves().stream()
                .map(l -> new Item(
                        slug(l.id()),
                        l.names(),
                        ServiceKind.of(l.id(), null),
                        providersByCategory.getOrDefault(l.id(), Set.of()).size()))
                .toList();
        return new Group(
                g.group().id(),
                slug(g.group().id()),
                g.group().names(),
                order.note(g.group().id()).orElse(null),
                items);
    }

    private record GroupNode(CategorySource.Category group, List<CategorySource.Category> leaves) {}

    private record Found(GroupNode group, CategorySource.Category leaf) {}

    /** Groups and their categories in the seed's order. */
    private List<GroupNode> taxonomy() {
        var all = categories.byRoots(List.of(ROOT));
        var groups = all.stream()
                .filter(c -> c.parentId() == null)
                .sorted(Comparator.comparingInt(c -> order.ordinal(c.id())))
                .toList();
        var leaves = all.stream()
                .filter(c -> c.parentId() != null)
                .collect(Collectors.groupingBy(c -> Objects.requireNonNull(c.parentId())));
        return groups.stream()
                .map(g -> new GroupNode(
                        g,
                        leaves.getOrDefault(g.id(), List.of()).stream()
                                .sorted(Comparator.comparingInt(c -> order.ordinal(c.id())))
                                .toList()))
                .toList();
    }

    private Found find(String slug) {
        for (var group : taxonomy()) {
            for (var leaf : group.leaves()) {
                if (slug(leaf.id()).equals(slug)) {
                    return new Found(group, leaf);
                }
            }
        }
        throw new NotFound("service category", slug);
    }

    /** {@code service.automotive.mobile-mechanic} → {@code mobile-mechanic}; a group → its own slug. */
    static String slug(String categoryId) {
        return categoryId.substring(categoryId.lastIndexOf('.') + 1);
    }
}
