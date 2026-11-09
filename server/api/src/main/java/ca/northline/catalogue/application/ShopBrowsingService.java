package ca.northline.catalogue.application;

import ca.northline.catalogue.application.BrowseShop.Place;
import ca.northline.catalogue.application.ShopCatalogue.Category;
import ca.northline.catalogue.application.ShopCatalogue.MoreRow;
import ca.northline.catalogue.application.ShopCatalogue.OfferRow;
import ca.northline.catalogue.application.ShopCatalogue.ProductRow;
import ca.northline.catalogue.application.ShopCatalogue.ShopStats;
import ca.northline.catalogue.application.ShopCatalogue.VariantRow;
import ca.northline.catalogue.application.ShopViews.DepartmentTile;
import ca.northline.catalogue.application.ShopViews.ProductCard;
import ca.northline.catalogue.application.ShopViews.Run;
import ca.northline.catalogue.application.ShopViews.ShopCard;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.merchants.api.ShopDirectory;
import ca.northline.merchants.api.ShopDirectory.Shop;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.region.api.AgeClass;
import ca.northline.region.api.AgeRules;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.PlaceParams;
import ca.northline.trust.api.RatingQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BrowseShop}: the market's active sellers (merchants), their approved live offers (catalogue), the next pooled
 * runs (orders). A shop or product is "on" a run when it has something in stock that goes on pooled runs and its
 * handling time lets it be packed for that run (same day → the next run, next day → a run from tomorrow on, …).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ShopBrowsingService implements BrowseShop {

    static final int LANDING_SHOPS = 12;
    static final int LANDING_PRODUCTS = 8;
    static final int DEPARTMENT_PRODUCTS = 48;
    static final String MEDIA_URL = "/api/v1/public/catalogue/media/";
    static final int MORE_FROM_SHOP = 3;

    private final ShopDirectory directory;
    private final ShopCatalogue catalogue;
    private final CategoryCatalog categories;
    private final MediaRepository media;
    private final DeliveryRuns deliveryRuns;
    private final RatingQuery ratings;
    private final Regions regions;
    private final AgeRules ageRules;
    private final Clock clock;

    @Override
    public ShopViews.Landing landing(Place place, Locale locale) {
        var ctx = context(place, locale);
        var nearby = nearby(ctx, null, LANDING_PRODUCTS);
        if (!ctx.served()) {
            return new ShopViews.Landing(ctx.market(), false, null, 0, List.of(), List.of(), List.of(), nearby);
        }
        var stats = catalogue.shops(ctx.merchantIds(), null, ctx.excluded());
        var perDepartment =
                stats.stream().collect(Collectors.groupingBy(ShopStats::departmentId, Collectors.counting()));
        var departments = perDepartment.entrySet().stream()
                .map(e -> Optional.ofNullable(ctx.categories().get(e.getKey()))
                        .map(c -> new DepartmentTile(
                                c.slug(), c.name(), e.getValue().intValue())))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(DepartmentTile::shops)
                        .reversed()
                        .thenComparing(DepartmentTile::name))
                .toList();
        var cards = shopCards(ctx, stats);
        var onRun = cards.stream().sorted(shopOrder(ctx)).limit(LANDING_SHOPS).toList();
        var popular = popular(ctx, null, LANDING_PRODUCTS);
        return new ShopViews.Landing(
                ctx.market(), true, ctx.firstRun(), cards.size(), departments, onRun, popular, nearby);
    }

    @Override
    public Optional<ShopViews.Department> department(String slug, Place place, Locale locale) {
        var ctx = context(place, locale);
        var leaf = ctx.categories().values().stream()
                .filter(c ->
                        c.leaf() && c.slug().equals(slug) && !ctx.excluded().contains(c.id()))
                .findFirst();
        return leaf.map(department -> {
            var group = ctx.categories().get(Objects.requireNonNull(department.parentId()));
            var groupName = group == null ? "" : group.name();
            var nearby = nearby(ctx, department.id(), LANDING_PRODUCTS);
            if (!ctx.served()) {
                return new ShopViews.Department(
                        department.slug(),
                        department.name(),
                        groupName,
                        ctx.market(),
                        false,
                        null,
                        List.of(new DepartmentTile(department.slug(), department.name(), 0)),
                        0,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        nearby);
            }
            var all = catalogue.shops(ctx.merchantIds(), null, ctx.excluded());
            var siblings = siblings(ctx, all, department);
            var stats = catalogue.shops(ctx.merchantIds(), department.id(), ctx.excluded());
            var shops = shopCards(ctx, stats).stream().sorted(shopOrder(ctx)).toList();
            var products = popular(ctx, department.id(), DEPARTMENT_PRODUCTS);
            var firstRun = ctx.firstRun();
            var onRun = (int) shops.stream()
                    .filter(s -> firstRun != null
                            && s.run() != null
                            && s.run().windowId().equals(firstRun.windowId()))
                    .count();
            return new ShopViews.Department(
                    department.slug(),
                    department.name(),
                    groupName,
                    ctx.market(),
                    true,
                    firstRun,
                    siblings,
                    shops.size(),
                    onRun,
                    catalogue.productCount(ctx.merchantIds(), department.id()),
                    shops,
                    products,
                    nearby);
        });
    }

    @Override
    public Optional<ShopViews.ProductPage> product(String productId, Place place, Locale locale) {
        var lang = "fr".equals(locale.getLanguage()) ? "fr" : "en";
        return catalogue
                .product(productId, lang)
                .filter(p -> !categories.banned().contains(p.categoryId()))
                .map(record -> {
                    var ctx = context(place, locale);
                    var department = ctx.categories().get(record.categoryId());
                    var own = ctx.served()
                            ? catalogue.offers(record.productId(), ctx.merchantIds())
                            : List.<OfferRow>of();
                    var away = ctx.market() == null
                            ? List.<OfferRow>of()
                            : catalogue.offers(record.productId(), ctx.otherIds());
                    var rows = new java.util.ArrayList<OfferRow>(own);
                    rows.addAll(away);
                    var offerIds = rows.stream().map(OfferRow::offerId).toList();
                    var variants = offerIds.isEmpty()
                            ? Map.<String, List<VariantRow>>of()
                            : catalogue.variants(offerIds).stream().collect(Collectors.groupingBy(VariantRow::offerId));
                    var merchants =
                            rows.stream().map(OfferRow::merchantId).distinct().toList();
                    var more = merchants.isEmpty()
                            ? Map.<String, List<MoreRow>>of()
                            : catalogue.moreFrom(merchants, record.productId(), lang, MORE_FROM_SHOP).stream()
                                    .collect(Collectors.groupingBy(MoreRow::merchantId));
                    var candidates = new HashSet<>(record.imageIds());
                    rows.forEach(r -> candidates.addAll(r.imageIds()));
                    variants.values().forEach(vs -> vs.forEach(v -> candidates.addAll(v.imageIds())));
                    var approved = candidates.isEmpty() ? Set.<String>of() : media.approved(candidates);
                    var offers = own.stream()
                            .<ShopViews.Offer>mapMulti((row, sink) -> {
                                var shop = ctx.shops().get(row.merchantId());
                                if (shop != null) {
                                    sink.accept(offer(ctx, shop, row, variants, more, record, approved, true));
                                }
                            })
                            .sorted(Comparator.comparingInt((ShopViews.Offer o) -> ctx.rank(o.shipsFrom()))
                                    .thenComparing(BEST_FIRST))
                            .toList();
                    var elsewhere = away.stream()
                            .<ShopViews.Offer>mapMulti((row, sink) -> {
                                var shop = ctx.shops().get(row.merchantId());
                                if (shop != null) {
                                    sink.accept(offer(ctx, shop, row, variants, more, record, approved, false));
                                }
                            })
                            .sorted(Comparator.comparingInt((ShopViews.Offer o) -> ctx.rank(o.shipsFrom()))
                                    .thenComparing(BEST_FIRST))
                            .toList();
                    var direct = ctx.served() && ctx.market() != null ? deliveryRuns.direct() : null;
                    var ageClass = categories
                            .profile(record.categoryId())
                            .map(CategoryProfile::ageClass)
                            .orElse(null);
                    return new ShopViews.ProductPage(
                            record.productId(),
                            record.name(),
                            record.brand(),
                            record.description(),
                            record.bullets(),
                            record.unit(),
                            department == null ? "" : department.slug(),
                            department == null ? "" : department.name(),
                            ctx.market(),
                            ctx.served(),
                            offers,
                            direct == null
                                    ? null
                                    : new ShopViews.Direct((int) direct.eta().toMinutes(), direct.feeCents()),
                            age(ctx, ageClass),
                            elsewhere);
                });
    }

    /**
     * The page's products. With a market, or with no point: by popularity over every shop of the page (one market, or
     * all of them). With no market but a point (S-190): market by market, nearest first, each by popularity.
     */
    private List<ProductCard> popular(Context ctx, @Nullable String categoryId, int limit) {
        if (ctx.market() != null || !ctx.hasPoint()) {
            return products(
                    ctx,
                    catalogue.popular(ctx.merchantIds(), categoryId, ctx.excluded(), ctx.lang(), limit),
                    ctx.deliverable());
        }
        return byMarket(ctx, ctx.markets(), categoryId, limit, null);
    }

    /** S-190: with a market, what the nearest other markets sell (labelled not deliverable); empty otherwise. */
    private List<ProductCard> nearby(Context ctx, @Nullable String categoryId, int limit) {
        if (ctx.market() == null) {
            return List.of();
        }
        return byMarket(ctx, ctx.others(), categoryId, limit, false);
    }

    /** Products market by market, in the given order, each market's by popularity, a product once, up to the limit. */
    private List<ProductCard> byMarket(
            Context ctx, List<String> markets, @Nullable String categoryId, int limit, @Nullable Boolean deliverable) {
        var out = new java.util.ArrayList<ProductCard>();
        var seen = new HashSet<String>();
        for (var m : markets) {
            if (out.size() >= limit) {
                break;
            }
            var ids = ctx.idsIn(m);
            if (ids.isEmpty()) {
                continue;
            }
            products(ctx, catalogue.popular(ids, categoryId, ctx.excluded(), ctx.lang(), limit), deliverable).stream()
                    .filter(p -> seen.add(p.productId()))
                    .limit(limit - out.size())
                    .forEach(out::add);
        }
        return List.copyOf(out);
    }

    /**
     * A market's shops by their run (soonest first), then name. Every market together (S-190): the nearest market
     * first when the point is known, then the higher tier, the better rated, the more products, the name.
     */
    private Comparator<ShopCard> shopOrder(Context ctx) {
        if (ctx.market() != null) {
            return Comparator.comparing((ShopCard s) ->
                            s.run() == null ? Instant.MAX : s.run().startsAt())
                    .thenComparing(ShopCard::name);
        }
        var rated = ratings.summaries(ctx.merchantIds());
        return Comparator.comparingInt((ShopCard s) -> ctx.rank(s.city()))
                .thenComparingInt(s -> tierRank(s.tier()))
                .thenComparingDouble(s -> {
                    var r = rated.get(s.merchantId());
                    return r == null ? 0 : -r.average();
                })
                .thenComparingInt(s -> -s.products())
                .thenComparing(ShopCard::name);
    }

    private static int tierRank(String tier) {
        return switch (tier) {
            case "master" -> 0;
            case "trusted" -> 1;
            default -> 2;
        };
    }

    /** In stock first, then the earliest run, then the lower price, then the higher tier. */
    static final Comparator<ShopViews.Offer> BEST_FIRST = Comparator.comparing((ShopViews.Offer o) -> o.stock() == 0)
            .thenComparing(
                    o -> o.runs().isEmpty() ? Instant.MAX : o.runs().getFirst().startsAt())
            .thenComparingLong(ShopViews.Offer::priceCents)
            .thenComparingInt(o -> tierRank(o.tier()))
            .thenComparing(ShopViews.Offer::offerId);

    private ShopViews.Offer offer(
            Context ctx,
            Shop shop,
            OfferRow row,
            Map<String, List<VariantRow>> variants,
            Map<String, List<MoreRow>> more,
            ShopCatalogue.ProductRecord record,
            Set<String> approved,
            boolean inMarket) {
        var own = variants.getOrDefault(row.offerId(), List.of()).stream()
                .map(v -> new ShopViews.Variant(
                        v.variantId(),
                        v.value(),
                        v.priceCents(),
                        v.stock(),
                        v.imageIds().stream()
                                .filter(approved::contains)
                                .map(id -> MEDIA_URL + id)
                                .toList()))
                .toList();
        var price = own.stream()
                .filter(v -> v.stock() > 0)
                .mapToLong(ShopViews.Variant::priceCents)
                .min()
                .orElse(
                        own.isEmpty()
                                ? row.priceCents()
                                : own.stream()
                                        .mapToLong(ShopViews.Variant::priceCents)
                                        .min()
                                        .orElse(row.priceCents()));
        var lowAt = row.lowStockAt();
        var images = (row.imageIds().isEmpty() ? record.imageIds() : row.imageIds())
                .stream().filter(approved::contains).map(id -> MEDIA_URL + id).toList();
        var rating = ratings.summary(shop.merchantId());
        return new ShopViews.Offer(
                row.offerId(),
                shop.merchantId(),
                shop.displayName(),
                shop.tier(),
                rating.average(),
                rating.count(),
                price,
                row.compareAtCents(),
                row.condition(),
                row.stock(),
                row.stock() > 0 && lowAt != null && row.stock() <= lowAt,
                row.returnsPolicy(),
                row.variantTheme(),
                own,
                inMarket ? ctx.runsFor(row.handlingDays(), 2) : List.of(),
                images,
                more.getOrDefault(shop.merchantId(), List.of()).stream()
                        .map(m -> new ShopViews.MoreItem(m.productId(), m.name(), m.priceCents()))
                        .toList(),
                ctx.marketOf(shop.merchantId()),
                inMarket ? ctx.deliverable() : Boolean.FALSE);
    }

    /** Departments of the same group with shops in the market (this one always), by name. */
    private static List<DepartmentTile> siblings(Context ctx, List<ShopStats> all, Category department) {
        var shopsPer = all.stream().collect(Collectors.groupingBy(ShopStats::departmentId, Collectors.counting()));
        return ctx.categories().values().stream()
                .filter(c -> c.leaf() && Objects.equals(c.parentId(), department.parentId()))
                .filter(c -> c.id().equals(department.id()) || shopsPer.containsKey(c.id()))
                .map(c -> new DepartmentTile(
                        c.slug(), c.name(), shopsPer.getOrDefault(c.id(), 0L).intValue()))
                .sorted(Comparator.comparing(DepartmentTile::name))
                .toList();
    }

    private List<ShopCard> shopCards(Context ctx, List<ShopStats> stats) {
        var images =
                stats.stream().map(ShopStats::imageId).filter(Objects::nonNull).toList();
        var approved = images.isEmpty() ? Set.<String>of() : media.approved(images);
        return stats.stream()
                .<ShopCard>mapMulti((s, sink) -> {
                    var shop = ctx.shops().get(s.merchantId());
                    var dept = ctx.categories().get(s.departmentId());
                    if (shop != null && dept != null) {
                        sink.accept(new ShopCard(
                                shop.merchantId(),
                                shop.displayName(),
                                shop.tier(),
                                dept.slug(),
                                dept.name(),
                                s.products(),
                                ctx.runFor(s.handlingDays()),
                                s.imageId() != null && approved.contains(s.imageId()) ? MEDIA_URL + s.imageId() : null,
                                ctx.marketOf(shop.merchantId())));
                    }
                })
                .toList();
    }

    private List<ProductCard> products(Context ctx, List<ProductRow> rows, @Nullable Boolean deliverable) {
        var images =
                rows.stream().map(ProductRow::imageId).filter(Objects::nonNull).toList();
        var approved = images.isEmpty() ? Set.<String>of() : media.approved(images);
        return rows.stream()
                .map(r -> {
                    var shop = ctx.shops().get(r.merchantId());
                    var image = r.imageId();
                    return new ProductCard(
                            r.productId(),
                            r.offerId(),
                            r.name(),
                            r.merchantId(),
                            shop == null ? "" : shop.displayName(),
                            r.unit(),
                            r.priceCents(),
                            image != null && approved.contains(image) ? MEDIA_URL + image : null,
                            r.sellers(),
                            Boolean.FALSE.equals(deliverable) ? null : ctx.runFor(r.handlingDays()),
                            age(ctx, AgeClass.of(r.ageClass()).orElse(null)),
                            r.categoryId(),
                            ctx.marketOf(r.merchantId()),
                            deliverable);
                })
                .toList();
    }

    /** What an age-restricted product needs in the market's province (region data); null when it isn't one. */
    private ShopViews.@Nullable Age age(Context ctx, @Nullable AgeClass ageClass) {
        if (ageClass == null) {
            return null;
        }
        var minimum = ageRules.rule(ctx.province(), ageClass)
                .map(AgeRules.AgeRule::minimumAge)
                .orElse(null);
        return new ShopViews.Age(ageClass.code(), minimum);
    }

    private Context context(Place place, Locale locale) {
        var now = clock.instant();
        var lang = "fr".equals(locale.getLanguage()) ? "fr" : "en";
        var cats = catalogue.categories(lang).stream()
                .collect(Collectors.toMap(Category::id, Function.identity(), (a, _) -> a));
        var asked = place.market();
        var served = asked == null ? Optional.<String>empty() : deliveryRuns.market(asked);
        var name = asked == null ? null : served.orElse(asked.strip());
        var origin = origin(place, name);
        // every market Northline delivers in, nearest first when there is a point to measure from
        var markets = deliveryRuns.markets().stream()
                .sorted(Comparator.comparingDouble(m -> distance(origin, m)))
                .toList();
        var shown = name == null ? markets : served.map(List::of).orElse(List.<String>of());
        var others = name == null
                ? List.<String>of()
                : markets.stream().filter(m -> !m.equalsIgnoreCase(name)).toList();
        var byMarket = new java.util.LinkedHashMap<String, List<String>>();
        var shops = new java.util.HashMap<String, Shop>();
        var marketOf = new java.util.HashMap<String, String>();
        var lists = new java.util.ArrayList<String>(shown);
        lists.addAll(others);
        for (var m : lists) {
            var ids = new java.util.ArrayList<String>();
            for (var shop : directory.shopsIn(m)) {
                if (shops.putIfAbsent(shop.merchantId(), shop) == null) {
                    marketOf.put(shop.merchantId(), m);
                    ids.add(shop.merchantId());
                }
            }
            byMarket.put(m, List.copyOf(ids));
        }
        var own = shown.stream()
                .flatMap(m -> byMarket.getOrDefault(m, List.of()).stream())
                .toList();
        var runs = name == null
                ? List.<DeliveryRuns.Run>of()
                : served.map(m -> deliveryRuns.upcoming(m, now)).orElse(List.of());
        return new Context(
                name,
                name == null ? !markets.isEmpty() : served.isPresent(),
                lang,
                shops,
                Set.copyOf(own),
                marketOf,
                byMarket,
                shown,
                others,
                origin != null,
                cats,
                new HashSet<>(categories.banned()),
                runs,
                now,
                name == null ? regions.platformZone() : deliveryRuns.zone(name),
                name == null
                        ? null
                        : regions.market(name, null)
                                .map(MarketProfile::province)
                                .orElse(null));
    }

    /** The point distances are measured from: the visitor's, else their market's centre (region data), else none. */
    private @Nullable Point origin(Place place, @Nullable String market) {
        var lat = place.lat();
        var lng = place.lng();
        if (lat != null && lng != null) {
            return new Point(lat, lng);
        }
        return market == null ? null : centre(market).orElse(null);
    }

    private Optional<Point> centre(String market) {
        return regions.market(market, null).flatMap(m -> {
            var lat = m.lat();
            var lng = m.lng();
            return lat == null || lng == null ? Optional.empty() : Optional.of(new Point(lat, lng));
        });
    }

    /**
     * Km from the origin to a market's centre. No origin: 0 for all (configuration order stays); a market without a
     * centre sorts after the measured ones.
     */
    private double distance(@Nullable Point origin, String market) {
        if (origin == null) {
            return 0;
        }
        return centre(market)
                .map(c -> PlaceParams.km(origin.lat(), origin.lng(), c.lat(), c.lng()))
                .orElse(Double.MAX_VALUE);
    }

    private record Point(double lat, double lng) {}

    /**
     * One request's market (null: every market, S-190), sellers, taxonomy and runs.
     *
     * @param shops every shop the page may show: the market's (or all markets'), and the other markets' for
     *     {@code nearby} / {@code elsewhere}
     * @param own the shops of the page's market (all markets' with none)
     * @param marketOf each shop's market, display form
     * @param byMarket the shops of each market listed
     * @param markets the markets the page shows, nearest first when measured
     * @param others with a market, the other markets, nearest first
     * @param measured distances are known (a point, or the market's centre)
     */
    private record Context(
            @Nullable String market,
            boolean served,
            String lang,
            Map<String, Shop> shops,
            Set<String> own,
            Map<String, String> marketOf,
            Map<String, List<String>> byMarket,
            List<String> markets,
            List<String> others,
            boolean measured,
            Map<String, Category> categories,
            Collection<String> excluded,
            List<DeliveryRuns.Run> runs,
            Instant now,
            ZoneId zone,
            @Nullable String province) {

        Collection<String> merchantIds() {
            return own;
        }

        Collection<String> otherIds() {
            return others.stream()
                    .flatMap(m -> byMarket.getOrDefault(m, List.of()).stream())
                    .toList();
        }

        Collection<String> idsIn(String market) {
            return byMarket.getOrDefault(market, List.of());
        }

        boolean hasPoint() {
            return measured;
        }

        /** Delivered to the visitor: true in their market, null when the market isn't known. */
        @Nullable
        Boolean deliverable() {
            return market == null ? null : Boolean.TRUE;
        }

        String marketOf(String merchantId) {
            return marketOf.getOrDefault(merchantId, "");
        }

        /** A market's position in the page's order (nearest first); unknown markets last. */
        int rank(String market) {
            var i = markets.indexOf(market);
            if (i >= 0) {
                return i;
            }
            var j = others.indexOf(market);
            return j < 0 ? Integer.MAX_VALUE : markets.size() + j;
        }

        @Nullable
        Run firstRun() {
            return runs.isEmpty() ? null : view(runs.getFirst());
        }

        /** The first run at least {@code handlingDays} days from today; null when not on any upcoming run. */
        @Nullable
        Run runFor(@Nullable Integer handlingDays) {
            return runsFor(handlingDays, 1).stream().findFirst().orElse(null);
        }

        /** Up to {@code limit} upcoming runs at least {@code handlingDays} days from today. */
        List<Run> runsFor(@Nullable Integer handlingDays, int limit) {
            if (handlingDays == null) {
                return List.of();
            }
            var today = LocalDate.ofInstant(now, zone);
            return runs.stream()
                    .filter(r ->
                            ChronoUnit.DAYS.between(today, LocalDate.ofInstant(r.startsAt(), zone)) >= handlingDays)
                    .limit(limit)
                    .map(this::view)
                    .toList();
        }

        private Run view(DeliveryRuns.Run r) {
            var days = ChronoUnit.DAYS.between(LocalDate.ofInstant(now, zone), LocalDate.ofInstant(r.startsAt(), zone));
            var day = days <= 0 ? "today" : days == 1 ? "tomorrow" : "later";
            return new Run(
                    r.windowId(),
                    r.label(),
                    day,
                    r.startsAt(),
                    r.endsAt(),
                    r.orderBy(),
                    r.packBy(),
                    r.feeCents(),
                    r.households());
        }
    }
}
