package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.*;

import ca.northline.catalogue.application.ListingRepository;
import ca.northline.catalogue.application.ListingRepository.BundleComponent;
import ca.northline.catalogue.application.ListingRepository.VariantFact;
import ca.northline.catalogue.domain.CatalogRecord;
import ca.northline.catalogue.domain.Fulfilment;
import ca.northline.catalogue.domain.HandlingTime;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.ItemCondition;
import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ListingState;
import ca.northline.catalogue.domain.ListingStatus;
import ca.northline.catalogue.domain.MaterialField;
import ca.northline.catalogue.domain.OfferType;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ProductDetails.BundleItem;
import ca.northline.catalogue.domain.ProductDetails.Variant;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ReturnsPolicy;
import ca.northline.catalogue.domain.ServiceDetails;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.VariantTheme;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.catalogue.domain.VettingFlag;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Listing aggregates over {@code catalogue.offers} (+ {@code variants}, content from {@code catalog_products}) and
 * {@code catalogue.services}. Saves are upserts; variants are replaced as a set.
 */
@Repository
@RequiredArgsConstructor
class ListingPersistenceAdapter implements ListingRepository {

    private final JdbcClient jdbc;
    private final CatalogRecordAdapter records;

    // ── reads ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<ProductListing> product(String merchantId, String listingId) {
        return jdbc.sql("select * from catalogue.offers where id = :id and merchant_id = :m")
                .param("id", listingId)
                .param("m", merchantId)
                .query(this::offerRow)
                .optional()
                .map(this::toProduct);
    }

    @Override
    public Optional<ServiceListing> service(String merchantId, String listingId) {
        return jdbc.sql("select * from catalogue.services where id = :id and merchant_id = :m")
                .param("id", listingId)
                .param("m", merchantId)
                .query((rs, _) -> toService(rs))
                .optional();
    }

    @Override
    public Optional<Listing> find(String merchantId, String listingId) {
        return product(merchantId, listingId)
                .<Listing>map(p -> p)
                .or(() -> service(merchantId, listingId).map(s -> s));
    }

    @Override
    public Optional<Listing> find(String listingId) {
        return jdbc.sql("""
                        select merchant_id from catalogue.offers where id = :id
                        union all select merchant_id from catalogue.services where id = :id
                        """)
                .param("id", listingId)
                .query(String.class)
                .optional()
                .flatMap(merchantId -> find(merchantId, listingId));
    }

    @Override
    public Optional<Listing> bySku(String merchantId, String sku) {
        return jdbc.sql("""
                        select id from catalogue.offers where merchant_id = :m and lower(sku) = lower(:sku)
                        union all select id from catalogue.services where merchant_id = :m and lower(sku) = lower(:sku)
                        limit 1
                        """)
                .param("m", merchantId)
                .param("sku", sku)
                .query(String.class)
                .optional()
                .flatMap(id -> find(merchantId, id));
    }

    @Override
    public boolean skuTaken(String merchantId, String sku, @Nullable String exceptListingId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (
                          select 1 from catalogue.offers where merchant_id = :m and lower(sku) = lower(:sku)
                            and id <> coalesce(cast(:except as text), '')
                          union all
                          select 1 from catalogue.services where merchant_id = :m and lower(sku) = lower(:sku)
                            and id <> coalesce(cast(:except as text), ''))
                        """)
                .param("m", merchantId)
                .param("sku", sku)
                .param("except", exceptListingId)
                .query(Boolean.class)
                .single());
    }

    // ── writes ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void save(ProductListing listing) {
        var d = listing.getDetails();
        var s = listing.getState();
        jdbc.sql("""
                        insert into catalogue.offers (id, product_id, merchant_id, title, sku, price_cents, compare_at_cents,
                          cost_cents, stock, low_stock_at, condition, fulfilment, vetting, status, variant_theme, image_source,
                          own_images, handling_time, returns_policy, country_of_origin, restricted_ok, bilingual_ok, warranty,
                          search_keywords, vetting_flags, revet_reasons, submitted_at, created_at, updated_at, listing_type)
                        values (:id, :product, :merchant, :title, :sku, :price, :compareAt, :cost, :stock, :lowStock, :condition,
                          :fulfilment, :vetting, :status, :theme, :imageSource, :ownImages, :handling, :returns, :origin,
                          :restrictedOk, :bilingualOk, :warranty, :keywords, :flags, :revet, :submittedAt, :createdAt,
                          :updatedAt, :type)
                        on conflict (id) do update set product_id = excluded.product_id, title = excluded.title,
                          sku = excluded.sku, price_cents = excluded.price_cents, compare_at_cents = excluded.compare_at_cents,
                          cost_cents = excluded.cost_cents, stock = excluded.stock, low_stock_at = excluded.low_stock_at,
                          condition = excluded.condition, fulfilment = excluded.fulfilment, vetting = excluded.vetting,
                          status = excluded.status, variant_theme = excluded.variant_theme,
                          image_source = excluded.image_source, own_images = excluded.own_images,
                          handling_time = excluded.handling_time, returns_policy = excluded.returns_policy,
                          country_of_origin = excluded.country_of_origin, restricted_ok = excluded.restricted_ok,
                          bilingual_ok = excluded.bilingual_ok, warranty = excluded.warranty,
                          search_keywords = excluded.search_keywords, vetting_flags = excluded.vetting_flags,
                          revet_reasons = excluded.revet_reasons, submitted_at = excluded.submitted_at,
                          updated_at = excluded.updated_at, listing_type = excluded.listing_type
                        """)
                .param("id", listing.getId())
                .param("type", d.type().code())
                .param("product", listing.getRecord().id())
                .param("merchant", listing.getMerchantId())
                .param("title", d.title())
                .param("sku", d.sku())
                .param("price", d.priceCents())
                .param("compareAt", d.compareAtCents())
                .param("cost", d.costCents())
                .param("stock", d.stock())
                .param("lowStock", d.lowStockAt())
                .param("condition", d.condition().code())
                .param("fulfilment", codes(d.fulfilment()))
                .param("vetting", s.getVetting().code())
                .param("status", s.getStatus().code())
                .param("theme", d.variantTheme().code())
                .param("imageSource", d.imageSource().code())
                .param("ownImages", array(d.ownImageIds()))
                .param("handling", code(d.handlingTime()))
                .param("returns", code(d.returnsPolicy()))
                .param("origin", d.countryOfOrigin())
                .param("restrictedOk", d.restrictedOk())
                .param("bilingualOk", d.bilingualOk())
                .param("warranty", d.warranty())
                .param("keywords", d.searchKeywords())
                .param("flags", codes(s.getFlags()))
                .param("revet", codes(s.getRevetReasons()))
                .param("submittedAt", ts(s.getSubmittedAt()))
                .param("createdAt", ts(s.getCreatedAt()))
                .param("updatedAt", ts(s.getUpdatedAt()))
                .update();
        jdbc.sql("delete from catalogue.variants where offer_id = :id")
                .param("id", listing.getId())
                .update();
        var position = 0;
        for (var v : d.variants()) {
            jdbc.sql("""
                            insert into catalogue.variants (id, offer_id, sku, gtin, attrs, price_cents, stock, image_set, position)
                            values (:id, :offer, :sku, :gtin, cast(:attrs as jsonb), :price, :stock, :images, :position)
                            """)
                    .param("id", v.id() == null ? Ids.next() : v.id())
                    .param("offer", listing.getId())
                    .param("sku", v.sku())
                    .param("gtin", v.gtin())
                    .param("attrs", json(Map.of("value", v.value())))
                    .param("price", v.priceCents())
                    .param("stock", v.stock())
                    .param("images", array(v.imageIds()))
                    .param("position", position++)
                    .update();
        }
        jdbc.sql("delete from catalogue.bundle_items where bundle_offer_id = :id")
                .param("id", listing.getId())
                .update();
        var line = 0;
        for (var item : d.bundleItems()) {
            jdbc.sql("""
                            insert into catalogue.bundle_items (bundle_offer_id, position, offer_id, variant_id, qty)
                            values (:bundle, :position, :offer, :variant, :qty)
                            """)
                    .param("bundle", listing.getId())
                    .param("position", line++)
                    .param("offer", item.offerId())
                    .param("variant", item.variantId())
                    .param("qty", item.qty())
                    .update();
        }
    }

    @Override
    public List<BundleComponent> bundleComponents(String merchantId, Collection<String> offerIds) {
        if (offerIds.isEmpty()) {
            return List.of();
        }
        var variants = jdbc
                .sql("""
                        select v.offer_id, v.id, coalesce(v.attrs ->> 'value', '') as value, coalesce(v.price_cents, 0) as price,
                               coalesce(v.stock, 0) as stock
                          from catalogue.variants v join catalogue.offers o on o.id = v.offer_id
                         where v.offer_id = any(:ids) and o.merchant_id = :m
                         order by v.position, v.sku
                        """)
                .param("ids", array(offerIds))
                .param("m", merchantId)
                .query((rs, _) -> Map.entry(
                        rs.getString("offer_id"),
                        new VariantFact(
                                rs.getString("id"), rs.getString("value"), rs.getLong("price"), rs.getInt("stock"))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.sql("""
                        select o.id, coalesce(nullif(o.title, ''), cp.title, '') as name, o.listing_type, o.vetting,
                               coalesce(o.price_cents, 0) as price, coalesce(o.stock, 0) as stock
                          from catalogue.offers o join catalogue.catalog_products cp on cp.id = o.product_id
                         where o.id = any(:ids) and o.merchant_id = :m
                        """)
                .param("ids", array(offerIds))
                .param("m", merchantId)
                .query((rs, _) -> new BundleComponent(
                        rs.getString("id"),
                        rs.getString("name"),
                        CodedEnum.fromCode(OfferType.class, rs.getString("listing_type")),
                        CodedEnum.fromCode(Vetting.class, rs.getString("vetting")),
                        rs.getLong("price"),
                        rs.getInt("stock"),
                        variants.getOrDefault(rs.getString("id"), List.of())))
                .list();
    }

    @Override
    public boolean inBundle(String offerId) {
        return Boolean.TRUE.equals(jdbc.sql("select exists (select 1 from catalogue.bundle_items where offer_id = :id)")
                .param("id", offerId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public void save(ServiceListing listing) {
        var d = listing.getDetails();
        var s = listing.getState();
        jdbc.sql("""
                        insert into catalogue.services (id, merchant_id, category_id, name, name_i18n, sku, included,
                          pricing_mode, price_cents, duration_min, buffer_min, instant_book, vetting, status, vetting_flags,
                          revet_reasons, submitted_at, created_at, updated_at)
                        values (:id, :merchant, :category, :name, cast(:nameI18n as jsonb), :sku, :included, :mode, :price,
                          :duration, :buffer, :instant, :vetting, :status, :flags, :revet, :submittedAt, :createdAt,
                          :updatedAt)
                        on conflict (id) do update set category_id = excluded.category_id, name = excluded.name,
                          name_i18n = catalogue.services.name_i18n || excluded.name_i18n, sku = excluded.sku,
                          included = excluded.included, pricing_mode = excluded.pricing_mode,
                          price_cents = excluded.price_cents, duration_min = excluded.duration_min,
                          buffer_min = excluded.buffer_min, instant_book = excluded.instant_book, vetting = excluded.vetting,
                          status = excluded.status, vetting_flags = excluded.vetting_flags,
                          revet_reasons = excluded.revet_reasons, submitted_at = excluded.submitted_at,
                          updated_at = excluded.updated_at
                        """)
                .param("id", listing.getId())
                .param("merchant", listing.getMerchantId())
                .param("category", d.categoryId())
                .param("name", d.name())
                .param("nameI18n", json(Map.of("en", d.name())))
                .param("sku", d.sku())
                .param("included", d.included())
                .param("mode", d.pricingMode().code())
                .param("price", d.priceCents())
                .param("duration", d.durationMin())
                .param("buffer", d.bufferMin())
                .param("instant", d.instantBook())
                .param("vetting", s.getVetting().code())
                .param("status", s.getStatus().code())
                .param("flags", codes(s.getFlags()))
                .param("revet", codes(s.getRevetReasons()))
                .param("submittedAt", ts(s.getSubmittedAt()))
                .param("createdAt", ts(s.getCreatedAt()))
                .param("updatedAt", ts(s.getUpdatedAt()))
                .update();
    }

    @Override
    public void delete(Listing listing) {
        switch (listing) {
            case ProductListing p -> {
                jdbc.sql("delete from catalogue.bundle_items where bundle_offer_id = :id")
                        .param("id", p.getId())
                        .update();
                jdbc.sql("delete from catalogue.variants where offer_id = :id")
                        .param("id", p.getId())
                        .update();
                jdbc.sql("delete from catalogue.offers where id = :id")
                        .param("id", p.getId())
                        .update();
            }
            case ServiceListing s ->
                jdbc.sql("delete from catalogue.services where id = :id")
                        .param("id", s.getId())
                        .update();
        }
    }

    // ── mapping ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The offer columns, read eagerly so the record and variants can be loaded after the result set closes. */
    private record OfferRow(
            String id,
            String productId,
            String merchantId,
            String title,
            @Nullable String sku,
            long priceCents,
            @Nullable Long compareAtCents,
            @Nullable Long costCents,
            int stock,
            @Nullable Integer lowStockAt,
            ItemCondition condition,
            java.util.List<Fulfilment> fulfilment,
            VariantTheme theme,
            ImageSource imageSource,
            java.util.List<String> ownImages,
            @Nullable HandlingTime handlingTime,
            @Nullable ReturnsPolicy returnsPolicy,
            @Nullable String countryOfOrigin,
            boolean restrictedOk,
            boolean bilingualOk,
            boolean warranty,
            @Nullable String searchKeywords,
            OfferType type,
            ListingState state) {}

    private OfferRow offerRow(ResultSet rs, int rowNum) throws SQLException {
        return new OfferRow(
                rs.getString("id"),
                rs.getString("product_id"),
                rs.getString("merchant_id"),
                Objects.requireNonNullElse(rs.getString("title"), ""),
                rs.getString("sku"),
                rs.getLong("price_cents"),
                longOrNull(rs, "compare_at_cents"),
                longOrNull(rs, "cost_cents"),
                rs.getInt("stock"),
                intOrNull(rs, "low_stock_at"),
                Objects.requireNonNullElse(enumOrNull(rs, "condition", ItemCondition.class), ItemCondition.NEW),
                enums(rs, "fulfilment", Fulfilment.class),
                CodedEnum.fromCode(VariantTheme.class, rs.getString("variant_theme")),
                CodedEnum.fromCode(ImageSource.class, rs.getString("image_source")),
                strings(rs, "own_images"),
                enumOrNull(rs, "handling_time", HandlingTime.class),
                enumOrNull(rs, "returns_policy", ReturnsPolicy.class),
                rs.getString("country_of_origin"),
                rs.getBoolean("restricted_ok"),
                rs.getBoolean("bilingual_ok"),
                rs.getBoolean("warranty"),
                rs.getString("search_keywords"),
                CodedEnum.fromCode(OfferType.class, rs.getString("listing_type")),
                state(rs));
    }

    private ProductListing toProduct(OfferRow o) {
        CatalogRecord record = records.byId(o.productId())
                .orElseThrow(() -> new IllegalStateException("Offer " + o.id() + " has no catalogue record"));
        var variants = jdbc.sql("select * from catalogue.variants where offer_id = :id order by position, sku")
                .param("id", o.id())
                .query((rs, _) -> new Variant(
                        rs.getString("id"),
                        stringMap(rs.getString("attrs")).getOrDefault("value", ""),
                        Objects.requireNonNullElse(rs.getString("sku"), ""),
                        rs.getString("gtin"),
                        rs.getLong("price_cents"),
                        rs.getInt("stock"),
                        strings(rs, "image_set")))
                .list();
        var bundle = o.type() == OfferType.BUNDLE
                ? jdbc.sql("select * from catalogue.bundle_items where bundle_offer_id = :id order by position")
                        .param("id", o.id())
                        .query((rs, _) ->
                                new BundleItem(rs.getString("offer_id"), rs.getString("variant_id"), rs.getInt("qty")))
                        .list()
                : List.<BundleItem>of();
        var details = new ProductDetails(
                record.identifierType(),
                record.gtin(),
                o.title(),
                record.brand(),
                record.mpn(),
                record.categoryId(),
                record.attributes(),
                record.description(),
                record.bullets(),
                o.theme(),
                variants,
                o.imageSource(),
                o.ownImages(),
                o.sku(),
                o.priceCents(),
                o.compareAtCents(),
                o.costCents(),
                o.condition(),
                o.stock(),
                o.lowStockAt(),
                o.fulfilment(),
                o.handlingTime(),
                o.returnsPolicy(),
                o.countryOfOrigin(),
                o.restrictedOk(),
                o.bilingualOk(),
                o.warranty(),
                o.searchKeywords(),
                o.type(),
                bundle);
        return ProductListing.builder()
                .id(o.id())
                .merchantId(o.merchantId())
                .details(details)
                .record(record)
                .state(o.state())
                .build();
    }

    private static ServiceListing toService(ResultSet rs) throws SQLException {
        var details = new ServiceDetails(
                Objects.requireNonNullElse(rs.getString("name"), ""),
                rs.getString("category_id"),
                Objects.requireNonNullElse(enumOrNull(rs, "pricing_mode", PricingMode.class), PricingMode.FIXED),
                longOrNull(rs, "price_cents"),
                rs.getInt("duration_min"),
                rs.getInt("buffer_min"),
                rs.getString("included"),
                rs.getBoolean("instant_book"),
                rs.getString("sku"));
        return ServiceListing.builder()
                .id(rs.getString("id"))
                .merchantId(rs.getString("merchant_id"))
                .details(details)
                .state(state(rs))
                .build();
    }

    private static ListingState state(ResultSet rs) throws SQLException {
        return ListingState.builder()
                .vetting(Objects.requireNonNullElse(enumOrNull(rs, "vetting", Vetting.class), Vetting.DRAFT))
                .status(Objects.requireNonNullElse(enumOrNull(rs, "status", ListingStatus.class), ListingStatus.HIDDEN))
                .flags(enums(rs, "vetting_flags", VettingFlag.class))
                .revetReasons(enums(rs, "revet_reasons", MaterialField.class))
                .submittedAt(instant(rs, "submitted_at"))
                .createdAt(requiredInstant(rs, "created_at"))
                .updatedAt(requiredInstant(rs, "updated_at"))
                .build();
    }

    private static @Nullable String code(@Nullable CodedEnum value) {
        return value == null ? null : value.code();
    }
}
