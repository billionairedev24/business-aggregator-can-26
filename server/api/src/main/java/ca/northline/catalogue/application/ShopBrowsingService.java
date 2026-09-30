package ca.northline.catalogue.application;

import ca.northline.catalogue.application.ShopCatalogue.Category;
import ca.northline.catalogue.application.ShopCatalogue.ProductRow;
import ca.northline.catalogue.application.ShopCatalogue.ShopStats;
import ca.northline.catalogue.application.ShopViews.DepartmentTile;
import ca.northline.catalogue.application.ShopViews.ProductCard;
import ca.northline.catalogue.application.ShopViews.Run;
import ca.northline.catalogue.application.ShopViews.ShopCard;
import ca.northline.merchants.api.ShopDirectory;
import ca.northline.merchants.api.ShopDirectory.Shop;
import ca.northline.orders.api.DeliveryRuns;
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

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final int LANDING_SHOPS = 12;
    static final int LANDING_PRODUCTS = 8;
    static final int DEPARTMENT_PRODUCTS = 48;
    static final String MEDIA_URL = "/api/v1/public/catalogue/media/";

    private final ShopDirectory directory;
    private final ShopCatalogue catalogue;
    private final CategoryCatalog categories;
    private final MediaRepository media;
    private final DeliveryRuns deliveryRuns;
    private final Clock clock;

    @Override
    public ShopViews.Landing landing(String market, Locale locale) {
        var ctx = context(market, locale);
        if (!ctx.served()) {
            return new ShopViews.Landing(ctx.market(), false, null, 0, List.of(), List.of(), List.of());
        }
        var stats = catalogue.shops(ctx.merchantIds(), null, ctx.excluded());
        var perDepartment =
                stats.stream().collect(Collectors.groupingBy(ShopStats::departmentId, Collectors.counting()));
        var departments = perDepartment.entrySet().stream()
                .map(e -> Optional.ofNullable(ctx.categories().get(e.getKey()))
                        .map(c -> new DepartmentTile(
                                c.slug(), c.name(), e.getValue().intValue())))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(DepartmentTile::shops).reversed().thenComparing(DepartmentTile::name))
                .toList();
        var cards = shopCards(ctx, stats);
        var onRun = cards.stream()
                .sorted(Comparator.comparing((ShopCard s) ->
                                s.run() == null ? Instant.MAX : s.run().startsAt())
                        .thenComparing(ShopCard::name))
                .limit(LANDING_SHOPS)
                .toList();
        var popular =
                products(ctx, catalogue.popular(ctx.merchantIds(), null, ctx.excluded(), ctx.lang(), LANDING_PRODUCTS));
        return new ShopViews.Landing(ctx.market(), true, ctx.firstRun(), cards.size(), departments, onRun, popular);
    }

    @Override
    public Optional<ShopViews.Department> department(String slug, String market, Locale locale) {
        var ctx = context(market, locale);
        var leaf = ctx.categories().values().stream()
                .filter(c ->
                        c.leaf() && c.slug().equals(slug) && !ctx.excluded().contains(c.id()))
                .findFirst();
        return leaf.map(department -> {
            var group = ctx.categories().get(Objects.requireNonNull(department.parentId()));
            var groupName = group == null ? "" : group.name();
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
                        List.of());
            }
            var all = catalogue.shops(ctx.merchantIds(), null, ctx.excluded());
            var siblings = siblings(ctx, all, department);
            var stats = catalogue.shops(ctx.merchantIds(), department.id(), ctx.excluded());
            var shops = shopCards(ctx, stats).stream()
                    .sorted(Comparator.comparing((ShopCard s) ->
                                    s.run() == null ? Instant.MAX : s.run().startsAt())
                            .thenComparing(ShopCard::name))
                    .toList();
            var products = products(
                    ctx,
                    catalogue.popular(
                            ctx.merchantIds(), department.id(), ctx.excluded(), ctx.lang(), DEPARTMENT_PRODUCTS));
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
                    products);
        });
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
                                ctx.runFor(s.handlingDays())));
                    }
                })
                .toList();
    }

    private List<ProductCard> products(Context ctx, List<ProductRow> rows) {
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
                            ctx.runFor(r.handlingDays()));
                })
                .toList();
    }

    private Context context(String market, Locale locale) {
        var now = clock.instant();
        var lang = "fr".equals(locale.getLanguage()) ? "fr" : "en";
        var served = deliveryRuns.market(market);
        var name = served.orElse(market.strip());
        var shops = served.map(directory::shopsIn).orElse(List.of()).stream()
                .collect(Collectors.toMap(Shop::merchantId, Function.identity(), (a, _) -> a));
        var runs = served.map(m -> deliveryRuns.upcoming(m, now)).orElse(List.of());
        var cats = catalogue.categories(lang).stream()
                .collect(Collectors.toMap(Category::id, Function.identity(), (a, _) -> a));
        return new Context(name, served.isPresent(), lang, shops, cats, new HashSet<>(categories.banned()), runs, now);
    }

    /** One request's market, sellers, taxonomy and runs. */
    private record Context(
            String market,
            boolean served,
            String lang,
            Map<String, Shop> shops,
            Map<String, Category> categories,
            Collection<String> excluded,
            List<DeliveryRuns.Run> runs,
            Instant now) {

        Collection<String> merchantIds() {
            return shops.keySet();
        }

        @Nullable
        Run firstRun() {
            return runs.isEmpty() ? null : view(runs.getFirst());
        }

        /** The first run at least {@code handlingDays} days from today; null when not on any upcoming run. */
        @Nullable
        Run runFor(@Nullable Integer handlingDays) {
            if (handlingDays == null) {
                return null;
            }
            var today = LocalDate.ofInstant(now, ZONE);
            return runs.stream()
                    .filter(r ->
                            ChronoUnit.DAYS.between(today, LocalDate.ofInstant(r.startsAt(), ZONE)) >= handlingDays)
                    .findFirst()
                    .map(this::view)
                    .orElse(null);
        }

        private Run view(DeliveryRuns.Run r) {
            var days = ChronoUnit.DAYS.between(LocalDate.ofInstant(now, ZONE), LocalDate.ofInstant(r.startsAt(), ZONE));
            var day = days <= 0 ? "today" : days == 1 ? "tomorrow" : "later";
            return new Run(
                    r.windowId(), r.label(), day, r.startsAt(), r.endsAt(), r.orderBy(), r.feeCents(), r.households());
        }
    }
}
