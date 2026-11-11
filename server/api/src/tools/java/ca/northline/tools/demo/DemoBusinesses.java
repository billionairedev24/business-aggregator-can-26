package ca.northline.tools.demo;

import ca.northline.tools.demo.DemoPlan.Business;
import ca.northline.tools.demo.DemoPlan.Listing;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * The demo's businesses, already verified and live (S-194): each business of plan.json with what onboarding, the
 * console's approval and the Studio's set-up would have left behind — the merchant (active, approved, its checklist
 * all verified, so nothing waits on Stripe's hosted pages), its owner as a member, a principal, its categories, a
 * published page with its sections, a point in the market, and its listings (services with hours and booking rules,
 * products with stock, a kitchen's hours, settings and live menu), each with a library photo. Everything goes through
 * the tables' own constraints and V016's triggers (category limits, principal roles and ownership, section kinds).
 */
final class DemoBusinesses {

    /** What was made, for the summary. */
    record Made(String merchantId, Business business, List<String> listingIds) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Db db;
    private final DemoPlan plan;
    private final DemoAccounts accounts;
    private final DemoPhotos photos;
    private final DemoMarket market;
    private final Instant now;
    private final List<Made> made = new ArrayList<>();
    private final Map<String, Integer> photosByDepartment = new TreeMap<>();

    DemoBusinesses(Db db, DemoPlan plan, DemoAccounts accounts, DemoPhotos photos, DemoMarket market, Instant now) {
        this.db = db;
        this.plan = plan;
        this.accounts = accounts;
        this.photos = photos;
        this.market = market;
        this.now = now;
    }

    static String merchantId(String key) {
        return DemoIds.of("merchant:" + key);
    }

    static String listingId(String businessKey, String listingKey) {
        return DemoIds.of("listing:" + businessKey + "." + listingKey);
    }

    List<Made> seed() {
        var zones = market.serviceZones(db);
        for (var b : plan.businesses()) {
            var owner =
                    "owner".equals(b.owner()) ? accounts.id("owner") : accounts.backgroundOwner(b.key(), b.name(), now);
            var id = merchant(b, owner, "active");
            page(b, id);
            location(b, id);
            List<String> listings;
            if (b.kitchen()) {
                listings = kitchen(b, id);
            } else if (b.provider()) {
                hours(id, owner, b.key(), zones);
                listings = b.listings().stream().map(l -> service(b, id, l)).toList();
            } else {
                listings = b.listings().stream().map(l -> offer(b, id, l)).toList();
            }
            made.add(new Made(id, b, listings));
        }
        return made;
    }

    Map<String, Integer> photosByDepartment() {
        return photosByDepartment;
    }

    /**
     * An application waiting in the console's verification queue: submitted yesterday, its checks passed, its owner a
     * background person (the demo's applicant account stays without a business, for the live onboarding).
     */
    String application(DemoPlan.Application a) {
        var owner = accounts.backgroundOwner(a.key(), a.name(), now);
        var b = new Business(
                a.key(),
                a.type(),
                a.name(),
                a.categories(),
                a.colour(),
                "registered",
                a.about(),
                a.about(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        var id = merchant(b, owner, "pending");
        db.update(
                "update merchants.merchants set onboarding_step = 'review', approved_at = null, submitted_at = ? where id = ?",
                now.minusSeconds(26 * 3600),
                id);
        return id;
    }

    // ── the merchant and its onboarding ─────────────────────────────────────────────────────────────────────────

    private String merchant(Business b, String owner, String status) {
        var id = merchantId(b.key());
        var bn = businessNumber(b.key());
        var legal = b.name() + (b.kitchen() ? " Ltd." : " Inc.");
        var since = now.minusSeconds(86_400L * (90 + Math.floorMod(b.key().hashCode(), 400)));
        var profile = new LinkedHashMap<String, Object>();
        profile.put("description", b.about());
        profile.put("languages", List.of("en", "fr"));
        profile.put("yearsOperating", "3-5");
        if (b.seller()) {
            profile.put("sameDayCutoff", b.sameDayCutoff() == null ? "17:45" : b.sameDayCutoff());
            profile.put("inventorySources", List.of("manual"));
            profile.put("perishables", List.of("none"));
        }
        if (b.kitchen()) {
            profile.put("cuisines", b.cuisines() == null ? List.of("other") : b.cuisines());
            profile.put("fulfilment", List.of("hot_courier", "pickup"));
            profile.put("dietary", List.of());
            profile.put("alcohol", b.licence() == null ? "none" : "with_food");
        }
        if (b.provider()) {
            profile.put("workLocations", List.of("customer"));
            profile.put("serviceArea", market.city());
        }
        var legalDetails = Map.of(
                "structure",
                "corp_prov",
                "legal_corporate_name",
                legal,
                "business_number",
                bn,
                "operating_name",
                b.name(),
                "registered_office",
                market.city() + ", " + market.province());
        db.update(
                """
                insert into merchants.merchants (id, type, display_name, legal_name, structure, legal_details, business_number,
                    gst_number, registry_ref, registry_jurisdiction, tier, take_rate_bps, status, region_id, languages, city,
                    province, work_email, profile, onboarding_step, business_terms_accepted_at, submitted_at, approved_at,
                    created_by, cancellation_policy, created_at, updated_at)
                values (?, ?, ?, ?, 'corp_prov', ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, '{en,fr}', ?, ?, ?, ?::jsonb, 'done', ?, ?, ?,
                        ?, '24h', ?, ?)
                on conflict (id) do update set type = excluded.type, display_name = excluded.display_name,
                    legal_name = excluded.legal_name, legal_details = excluded.legal_details, tier = excluded.tier,
                    take_rate_bps = excluded.take_rate_bps, status = excluded.status, region_id = excluded.region_id,
                    city = excluded.city, province = excluded.province, profile = excluded.profile,
                    onboarding_step = excluded.onboarding_step, approved_at = excluded.approved_at,
                    search_hidden_at = null, search_hidden_cause = null, updated_at = excluded.updated_at
                """,
                id,
                b.type(),
                b.name(),
                legal,
                json(legalDetails),
                bn,
                bn + " RT0001",
                "DEMO-" + bn,
                market.province(),
                b.tier(),
                takeRate(b.tier()),
                status,
                market.id(),
                market.city(),
                market.province(),
                "hello@" + slug(b.name()) + "." + DemoAccounts.DOMAIN,
                json(profile),
                since,
                since.plusSeconds(3600),
                "active".equals(status) ? since.plusSeconds(86_400) : null,
                owner,
                since,
                now);
        db.update("""
                insert into merchants.merchant_members (merchant_id, user_id, role, bookable, mfa_ok, joined_at)
                values (?, ?, 'owner', ?, true, ?)
                on conflict (merchant_id, user_id) do update set role = 'owner', bookable = excluded.bookable, mfa_ok = true
                """, id, owner, b.provider(), since);
        var kyc = verification(id, "kyc", "kyc", null, "passed", 0, "stripe", null);
        db.update("""
                insert into merchants.merchant_principals (id, merchant_id, legal_name, role, ownership_pct, kyc_verification_id, user_id)
                values (?, ?, ?, 'director', 100, ?, ?)
                on conflict (id) do update set legal_name = excluded.legal_name, kyc_verification_id = excluded.kyc_verification_id
                """, DemoIds.of("principal:" + b.key()), id, ownerName(owner), kyc, owner);
        int pos = 1;
        verification(id, "registry", "registry", null, "DEMO-" + bn, pos++, "registry", null);
        verification(id, "gst", "registry", "CRA", bn + " RT0001", pos++, "registry", null);
        for (var c : b.categories()) {
            var registry = db.text("select regulated_registry from catalogue.categories where id = ?", c)
                    .orElse(null);
            String registryCheck = null;
            if (registry != null && !registry.isBlank()) {
                registryCheck = verification(
                        id,
                        "licence:" + registry,
                        "licence",
                        registry,
                        "DEMO-" + Math.floorMod(c.hashCode(), 100000),
                        pos++,
                        "registry",
                        now.plusSeconds(86_400L * 300));
            }
            db.update("""
                    insert into merchants.merchant_categories (merchant_id, category_id, status, registry_verification_id)
                    values (?, ?, 'approved', ?)
                    on conflict (merchant_id, category_id) do update set status = 'approved', registry_verification_id = excluded.registry_verification_id
                    """, id, c, registryCheck);
        }
        if (b.provider()) {
            verification(
                    id,
                    "insurance",
                    "insurance",
                    null,
                    "Liability $2M",
                    pos++,
                    "agent",
                    now.plusSeconds(86_400L * 200));
        }
        if (b.kitchen()) {
            verification(
                    id,
                    "ahs_permit",
                    "ahs_permit",
                    null,
                    "FS-" + bn.substring(0, 6),
                    pos++,
                    "registry",
                    now.plusSeconds(86_400L * 250));
            verification(id, "food_cert", "food_cert", null, "2 staff", pos++, "agent", null);
            verification(id, "allergen_attestation", "attestation", null, "signed", pos++, "owner", null);
        }
        if (b.seller()) {
            verification(id, "product_safety", "attestation", null, "signed", pos++, "owner", null);
            verification(id, "returns_policy", "attestation", null, "standard", pos++, "owner", null);
        }
        verification(id, "bank", "bank", null, "Demo bank ··" + bn.substring(5, 9), pos++, "stripe", null);
        verification(id, "mfa", "mfa", null, "second_factor", pos, "system", null);
        db.update("""
                insert into merchants.obligation_acceptances (merchant_id, version, accepted_by, accepted_at)
                values (?, '2.3', ?, ?) on conflict do nothing
                """, id, owner, since);
        if (b.licence() != null) {
            licence(id, b.licence(), owner, since);
        }
        return id;
    }

    private String verification(
            String merchantId,
            String checkKey,
            String checkType,
            @Nullable String registry,
            @Nullable String reference,
            int position,
            String by,
            @Nullable Instant expires) {
        var id = DemoIds.of("verification:" + merchantId + ":" + checkKey);
        db.update("""
                insert into merchants.verifications (id, merchant_id, check_key, check_type, registry, status, reference, position,
                    verified_by, expires_at, verified_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'verified', ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set status = 'verified', reference = excluded.reference, position = excluded.position,
                    expires_at = excluded.expires_at, updated_at = excluded.updated_at
                """, id, merchantId, checkKey, checkType, registry, reference, position, by, expires, now, now, now);
        return id;
    }

    /** An approved licence for an age-restricted class (alcohol, tobacco), with the document it was approved on. */
    private void licence(String merchantId, String ageClass, String owner, Instant since) {
        var doc = DemoIds.of("document:licence:" + merchantId);
        db.update("""
                insert into merchants.documents (id, merchant_id, purpose, file_name, content_type, size_bytes, storage_key, uploaded_by, created_at)
                values (?, ?, 'verification', 'licence.pdf', 'application/pdf', 48211, ?, ?, ?) on conflict (id) do nothing
                """, doc, merchantId, "demo/" + merchantId + "/licence.pdf", owner, since);
        db.update(
                """
                insert into merchants.restricted_licences (id, merchant_id, age_class, province, licence_number, document_id, expires_on,
                    status, submitted_by, submitted_at, decided_by, decided_at)
                values (?, ?, ?, ?, ?, ?, ?, 'approved', ?, ?, ?, ?)
                on conflict (id) do update set status = 'approved', province = excluded.province, expires_on = excluded.expires_on
                """,
                DemoIds.of("licence:" + merchantId + ":" + ageClass),
                merchantId,
                ageClass,
                market.province(),
                "DEMO-" + ageClass.toUpperCase(Locale.ROOT) + "-" + merchantId.substring(20),
                doc,
                LocalDate.ofInstant(now, market.zone()).plusYears(1),
                owner,
                since,
                accounts.id("admin"),
                since.plusSeconds(86_400));
    }

    // ── public page and place ───────────────────────────────────────────────────────────────────────────────────

    private void page(Business b, String merchantId) {
        var id = DemoIds.of("storefront:" + b.key());
        var slug = freeSlug(slug(b.name()), id);
        var kind = b.provider() ? "business_page" : b.seller() ? "store" : "menu_page";
        var cta = b.provider() ? "book_visit" : "order_now";
        db.update(
                """
                insert into merchants.storefronts (id, merchant_id, slug, page_kind, brand_color, tagline_i18n, cta_label, published_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                on conflict (id) do update set slug = excluded.slug, page_kind = excluded.page_kind, brand_color = excluded.brand_color,
                    tagline_i18n = excluded.tagline_i18n, cta_label = excluded.cta_label,
                    published_at = coalesce(merchants.storefronts.published_at, excluded.published_at), updated_at = excluded.updated_at
                """,
                id,
                merchantId,
                slug,
                kind,
                b.colour(),
                json(Map.of("en", b.tagline())),
                cta,
                now.minusSeconds(86_400L * 30),
                now.minusSeconds(86_400L * 31),
                now);
        var sections = switch (kind) {
            case "business_page" -> List.of("hero", "about", "services", "reviews", "area", "faq", "cta");
            case "store" -> List.of("hero", "about", "featured", "catalogue", "delivery", "reviews", "policies", "cta");
            default -> List.of("hero", "about", "menu", "hours", "fulfil", "reviews", "permit", "cta");
        };
        for (int i = 0; i < sections.size(); i++) {
            var k = sections.get(i);
            var settings = "faq".equals(k)
                    ? json(Map.of(
                            "pairs",
                            List.of(Map.of(
                                    "q",
                                    "How do I book?",
                                    "a",
                                    "Pick a time on this page; you pay into escrow and we're paid after the job."))))
                    : "policies".equals(k)
                            ? json(Map.of(
                                    "returns_text",
                                    "Returns within 14 days for unopened items. Perishables are final sale."))
                            : "{}";
            db.update("""
                    insert into merchants.storefront_sections (id, storefront_id, kind, position, enabled, settings)
                    values (?, ?, ?, ?, true, ?::jsonb) on conflict (id) do update set position = excluded.position, enabled = true
                    """, DemoIds.of("section:" + b.key() + ":" + k), id, k, i, settings);
        }
    }

    private String freeSlug(String wanted, String storefrontId) {
        var slug = wanted.length() > 40 ? wanted.substring(0, 40).replaceAll("-+$", "") : wanted;
        for (int n = 2;
                db.count("select count(*) from merchants.storefronts where slug = ? and id <> ?", slug, storefrontId)
                        > 0;
                n++) {
            var suffix = "-" + n;
            slug = (wanted.length() + suffix.length() > 40 ? wanted.substring(0, 40 - suffix.length()) : wanted)
                    + suffix;
        }
        return slug;
    }

    private void location(Business b, String merchantId) {
        var p = market.pointFor("merchant:" + b.key());
        db.update("""
                insert into merchants.locations (merchant_id, geom, service_radius_km, source, updated_at)
                values (?, ST_GeogFromText(?), ?, 'seed', ?)
                on conflict (merchant_id) do update set geom = excluded.geom, service_radius_km = excluded.service_radius_km, updated_at = excluded.updated_at
                """, merchantId, DemoMarket.point(p[0], p[1]), b.kitchen() ? null : 30, now);
    }

    // ── providers: hours, booking rules, areas, services ────────────────────────────────────────────────────────

    /** Bookable every day: Monday–Saturday 8–19, Sunday 10–16 (the market's local time), instant booking. */
    private void hours(String merchantId, String member, String key, List<String> zones) {
        var today = LocalDate.ofInstant(now, market.zone());
        for (int d = 1; d <= 7; d++) {
            var ranges = d == 7 ? "[[\"10:00\",\"16:00\"]]" : "[[\"08:00\",\"19:00\"]]";
            db.update(
                    """
                    insert into availability.availability_rules (id, merchant_id, member_user_id, weekday, ranges, effective_from, updated_at)
                    values (?, ?, ?, ?, ?::jsonb, ?, ?)
                    on conflict (id) do update set ranges = excluded.ranges, member_user_id = excluded.member_user_id, updated_at = excluded.updated_at
                    """,
                    DemoIds.of("availability:" + key + ":" + d),
                    merchantId,
                    member,
                    d,
                    ranges,
                    today.minusDays(60),
                    now);
        }
        db.update("""
                insert into availability.booking_rules (merchant_id, interval_min, buffer_min, min_notice_min, horizon_days, max_jobs_per_day,
                    accept_mode, reschedule_free_min, late_cancel_fee_cents, updated_at)
                values (?, 30, 15, 60, 30, 8, 'instant', 180, 0, ?)
                on conflict (merchant_id) do update set accept_mode = 'instant', horizon_days = 30, updated_at = excluded.updated_at
                """, merchantId, now);
        for (var z : zones.subList(0, Math.min(zones.size(), 6))) {
            db.update(
                    "insert into availability.service_areas (merchant_id, zone) values (?, ?) on conflict do nothing",
                    merchantId,
                    z);
        }
    }

    private String service(Business b, String merchantId, Listing l) {
        var id = listingId(b.key(), l.key());
        var category = l.categoryOr(b);
        var image = photo(merchantId, id, l, category);
        var quote = "quote".equals(l.pricingMode());
        db.update(
                """
                insert into catalogue.services (id, merchant_id, category_id, name, name_i18n, desc_i18n, sku, included, pricing_mode,
                    price_cents, duration_min, buffer_min, instant_book, vetting, status, own_images, sales_30d, submitted_at, created_at, updated_at)
                values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, 15, ?, 'approved', 'live', ?, ?, ?, ?, ?)
                on conflict (id) do update set category_id = excluded.category_id, name = excluded.name, name_i18n = excluded.name_i18n,
                    desc_i18n = excluded.desc_i18n, included = excluded.included, pricing_mode = excluded.pricing_mode,
                    price_cents = excluded.price_cents, duration_min = excluded.duration_min, instant_book = excluded.instant_book,
                    vetting = 'approved', status = 'live', own_images = excluded.own_images, updated_at = excluded.updated_at
                """,
                id,
                merchantId,
                category,
                l.title(),
                json(Map.of("en", l.title())),
                json(Map.of("en", l.text())),
                "DEMO-" + l.key().toUpperCase(Locale.ROOT),
                l.text(),
                l.pricingMode(),
                quote ? null : l.priceCents(),
                l.durationMin() == null ? 60 : l.durationMin(),
                !quote,
                image == null ? Db.array() : Db.array(image),
                5 + Math.floorMod(id.hashCode(), 40),
                now.minusSeconds(86_400L * 20),
                now.minusSeconds(86_400L * 21),
                now);
        return id;
    }

    // ── sellers: catalogue records and offers ───────────────────────────────────────────────────────────────────

    private String offer(Business b, String merchantId, Listing l) {
        var id = listingId(b.key(), l.key());
        var product = DemoIds.of("product:" + b.key() + "." + l.key());
        var category = l.categoryOr(b);
        var restricted = category.startsWith("shop.restricted.");
        db.update(
                """
                insert into catalogue.catalog_products (id, ref, identifier_type, title, title_i18n, category_id, attributes, description,
                    bullets, owner_merchant_id, created_by_merchant_id, locked, created_at, updated_at)
                values (?, ?, 'none', ?, ?::jsonb, ?, ?::jsonb, ?, '{}', ?, ?, false, ?, ?)
                on conflict (id) do update set title = excluded.title, title_i18n = excluded.title_i18n, category_id = excluded.category_id,
                    attributes = excluded.attributes, description = excluded.description, updated_at = excluded.updated_at
                """,
                product,
                "NL-D-" + product.substring(18),
                l.title(),
                json(Map.of("en", l.title())),
                category,
                json(Map.of("volume", l.unit() == null ? "each" : l.unit())),
                l.text(),
                merchantId,
                merchantId,
                now.minusSeconds(86_400L * 21),
                now);
        var image = photo(merchantId, id, l, category);
        db.update(
                """
                insert into catalogue.offers (id, product_id, merchant_id, title, sku, price_cents, compare_at_cents, stock, low_stock_at,
                    condition, fulfilment, vetting, status, variant_theme, image_source, own_images, handling_time, returns_policy,
                    country_of_origin, restricted_ok, bilingual_ok, search_keywords, sales_30d, submitted_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 5, 'new', '{pooled,pickup}', 'approved', 'live', 'none', 'own', ?, 'same_day', ?, 'CA',
                        true, true, ?, ?, ?, ?, ?)
                on conflict (id) do update set title = excluded.title, price_cents = excluded.price_cents,
                    compare_at_cents = excluded.compare_at_cents, stock = excluded.stock, vetting = 'approved', status = 'live',
                    own_images = excluded.own_images, returns_policy = excluded.returns_policy, licence_hold = false,
                    updated_at = excluded.updated_at
                """,
                id,
                product,
                merchantId,
                l.title(),
                "DEMO-" + b.key().toUpperCase(Locale.ROOT) + "-" + l.key().toUpperCase(Locale.ROOT),
                l.priceCents(),
                l.compareAtCents(),
                l.stock() == null ? 20 : l.stock(),
                image == null ? Db.array() : Db.array(image),
                restricted ? "final_sale" : "standard_14",
                l.title().toLowerCase(Locale.ROOT),
                5 + Math.floorMod(id.hashCode(), 60),
                now.minusSeconds(86_400L * 20),
                now.minusSeconds(86_400L * 21),
                now);
        return id;
    }

    /** The listing's photo (its own slot, else its category's), stored and recorded; null when the library has none. */
    private @Nullable String photo(String merchantId, String listingId, Listing l, String category) {
        var slot = l.photo() != null && photos.has(l.photo()) ? l.photo() : "category/" + category;
        var mediaId = DemoIds.of("media:" + listingId);
        var stored = photos.store(slot, merchantId, mediaId);
        if (stored.isEmpty()) {
            return null;
        }
        var p = stored.get();
        db.update("""
                insert into catalogue.media (id, url, kind, merchant_id, content_type, width, height, byte_size, on_white, exif_ok, created_at)
                values (?, ?, 'main', ?, 'image/jpeg', ?, ?, ?, false, true, ?)
                on conflict (id) do update set url = excluded.url, width = excluded.width, height = excluded.height, byte_size = excluded.byte_size
                """, mediaId, p.storageKey(), merchantId, p.width(), p.height(), p.bytes(), now);
        photosByDepartment.merge(category.substring(0, category.indexOf('.')), 1, Integer::sum);
        return mediaId;
    }

    // ── kitchens: settings, hours, a live menu ──────────────────────────────────────────────────────────────────

    private List<String> kitchen(Business b, String merchantId) {
        db.update("""
                insert into food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15, fulfilment, radius_km, group_orders, updated_at)
                values (?, 25, 8, '{courier,pickup}', 12, true, ?)
                on conflict (merchant_id) do update set paused_until = null, auto_paused_at = null, radius_km = 12, updated_at = excluded.updated_at
                """, merchantId, now);
        var hours = b.hours() == null ? "11:00-22:00" : b.hours();
        var from = hours.substring(0, 5);
        var to = hours.substring(6);
        if ("24:00".equals(to)) {
            to = "23:59";
        }
        for (int d = 1; d <= 7; d++) {
            db.update("""
                    insert into food.opening_hours (merchant_id, weekday, ranges, updated_at) values (?, ?, ?::jsonb, ?)
                    on conflict (merchant_id, weekday) do update set ranges = excluded.ranges, updated_at = excluded.updated_at
                    """, merchantId, d, "[[\"" + from + "\",\"" + to + "\"]]", now);
        }
        var menu = DemoIds.of("menu:" + b.key());
        db.update("""
                insert into food.menus (id, merchant_id, name, name_i18n, schedule, status, sort, published_at, created_at, updated_at)
                values (?, ?, 'Menu', '{"en":"Menu","fr":"Menu"}', '{"mode":"open_hours","days":[]}', 'live', 0, ?, ?, ?)
                on conflict (id) do update set status = 'live', published_at = coalesce(food.menus.published_at, excluded.published_at), updated_at = excluded.updated_at
                """, menu, merchantId, now.minusSeconds(86_400L * 30), now.minusSeconds(86_400L * 31), now);
        var items = new ArrayList<String>();
        int s = 0;
        for (var section : b.menuSections()) {
            var sectionId = DemoIds.of("menu-section:" + b.key() + ":" + s);
            db.update("""
                    insert into food.menu_sections (id, menu_id, name, name_i18n, sort) values (?, ?, ?, ?::jsonb, ?)
                    on conflict (id) do update set name = excluded.name, name_i18n = excluded.name_i18n, sort = excluded.sort
                    """, sectionId, menu, section.name(), json(Map.of("en", section.name())), s);
            int i = 0;
            for (var dish : section.items()) {
                var id = DemoIds.of("dish:" + b.key() + ":" + s + ":" + i);
                var ageClass = dish.dietary().contains("alcohol") ? "alcohol" : null;
                db.update(
                        """
                        insert into food.menu_items (id, section_id, merchant_id, name, name_i18n, description, desc_i18n, price_cents,
                            allergens, dietary, prep_add_min, sold_today, available, vetting, status, availability, combo_eligible,
                            sort, published_at, age_class, created_at, updated_at)
                        values (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, 0, 0, true, 'approved', 'published', 'always', true, ?, ?, ?, ?, ?)
                        on conflict (id) do update set name = excluded.name, description = excluded.description, price_cents = excluded.price_cents,
                            allergens = excluded.allergens, dietary = excluded.dietary, available = true, sold_out_on = null,
                            vetting = 'approved', status = 'published', updated_at = excluded.updated_at
                        """,
                        id,
                        sectionId,
                        merchantId,
                        dish.name(),
                        json(Map.of("en", dish.name())),
                        dish.text(),
                        json(Map.of("en", dish.text())),
                        dish.priceCents(),
                        Db.array(dish.allergens()),
                        Db.array(dish.dietary().stream()
                                .filter(x -> !"alcohol".equals(x))
                                .toList()),
                        i,
                        now.minusSeconds(86_400L * 30),
                        ageClass,
                        now.minusSeconds(86_400L * 31),
                        now);
                items.add(id);
                i++;
            }
            s++;
        }
        return items;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

    private String ownerName(String userId) {
        return db.text("select display_name from identity.users where id = ?", userId)
                .orElse("Demo Owner");
    }

    static String slug(String name) {
        var plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT)
                .replace("&", "and")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /** A fake 9-digit business number, stable for the key (never a real one's checksum pattern: starts with 9). */
    static String businessNumber(String key) {
        var n = Math.floorMod(key.hashCode(), 100_000_000);
        return "9" + String.format(Locale.ROOT, "%08d", n);
    }

    private static int takeRate(String tier) {
        return switch (tier) {
            case "master" -> 900;
            case "trusted" -> 1200;
            default -> 1500;
        };
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
