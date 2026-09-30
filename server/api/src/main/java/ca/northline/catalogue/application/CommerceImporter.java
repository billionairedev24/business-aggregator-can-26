package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceCatalogSource.ExternalProduct;
import ca.northline.catalogue.application.CommerceCatalogSource.ExternalVariant;
import ca.northline.catalogue.application.CommerceLinkRepository.ProductLink;
import ca.northline.catalogue.application.CommerceLinkRepository.VariantLink;
import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.catalogue.domain.Fulfilment;
import ca.northline.catalogue.domain.Gtin;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.ItemCondition;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ProductDetails.PriceStock;
import ca.northline.catalogue.domain.ProductDetails.Variant;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.VariantTheme;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.shared.RuleViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Turns one platform product into a Northline listing (S-35), inside the caller's transaction:
 *
 * <ul>
 *   <li><b>New</b> — a single-variant product whose SKU the merchant already sells is linked to that listing, whose
 *       price and stock are updated (the design: "existing SKUs are updated"); anything else becomes a <b>draft</b>
 *       through the editor's use case, with its images downloaded. Drafts stay private until the merchant completes
 *       and submits them for vetting (category, fulfilment and the compliance attestations can't come from a
 *       platform).
 *   <li><b>Known</b> — price and stock always follow the platform (its source of truth). Title, description, images
 *       and variants follow it only while the listing is still a draft; once submitted, Northline's vetted content
 *       stays (re-vetting on every platform edit would take live listings down).
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CommerceImporter {

    enum Result {
        CREATED,
        UPDATED,
        UNCHANGED,
        SKIPPED
    }

    static final String SYNC_ACTOR = "system:commerce";
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(p|div|li|h[1-6])>|<br\\s*/?>");

    private final EditProduct editProduct;
    private final ListingRepository listings;
    private final ManageMedia media;
    private final ImageInspector inspector;
    private final ImageFetcher images;
    private final CommerceLinkRepository links;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Result apply(Connection connection, ExternalProduct product, @Nullable ProductLink link, Set<String> linkedOffers) {
        var merchantId = connection.merchantId();
        var hash = contentHash(product);
        if (link != null) {
            var found = listings.product(merchantId, link.offerId());
            if (found.isEmpty()) {
                return Result.SKIPPED; // the merchant deleted it in Northline: not imported again
            }
            var listing = found.get();
            if (listing.getState().getVetting() == Vetting.DRAFT && !hash.equals(link.contentHash())) {
                var view = editProduct.update(new EditProduct.Command(
                        merchantId,
                        listing.getId(),
                        details(connection.provider(), product, listing.getDetails(), imageIds(merchantId, product)),
                        SYNC_ACTOR));
                save(connection, product, view.listing(), hash);
                return Result.UPDATED;
            }
            var changed = syncStock(connection.provider(), product, listing);
            save(connection, product, listing, link.contentHash());
            return changed ? Result.UPDATED : Result.UNCHANGED;
        }
        var existing = existingBySku(merchantId, product, linkedOffers);
        if (existing != null) {
            syncStock(connection.provider(), product, existing);
            save(connection, product, existing, hash);
            return Result.UPDATED;
        }
        var draft = details(connection.provider(), product, null, List.of());
        // rules first, so a product that can't be imported costs no image downloads
        var problems = draft.validate(null);
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        var view = editProduct.create(new EditProduct.Command(
                merchantId,
                null,
                details(connection.provider(), product, null, imageIds(merchantId, product)),
                SYNC_ACTOR));
        save(connection, product, view.listing(), hash);
        return Result.CREATED;
    }

    /**
     * Price and stock per SKU from the platform; @return whether the listing changed (it is saved then). A price
     * change on an approved listing sends it back to vetting (S-39).
     */
    private boolean syncStock(CommerceProvider provider, ExternalProduct product, ProductListing listing) {
        var revet = listing.syncStock(bySku(provider, product, listing), SYNC_ACTOR, clock.instant());
        revet.ifPresent(published -> {
            listings.save(listing);
            published.forEach(events::publishEvent);
        });
        return revet.isPresent();
    }

    /** The platform's price and stock keyed by the Northline SKU each variant maps to. */
    private static Map<String, PriceStock> bySku(CommerceProvider provider, ExternalProduct p, ProductListing listing) {
        var out = new HashMap<String, PriceStock>();
        var d = listing.getDetails();
        if (d.variants().isEmpty()) {
            var sku = d.sku();
            if (sku != null) {
                var total =
                        p.variants().stream().mapToInt(ExternalVariant::stock).sum();
                var price = p.variants().stream()
                        .mapToLong(ExternalVariant::priceCents)
                        .min()
                        .orElse(d.priceCents());
                out.put(sku, new PriceStock(price, Math.max(0, total)));
            }
            return out;
        }
        var skus = variantSkus(provider, p);
        for (var v : p.variants()) {
            out.put(Objects.requireNonNull(skus.get(v.id())), new PriceStock(v.priceCents(), Math.max(0, v.stock())));
        }
        return out;
    }

    private @Nullable ProductListing existingBySku(String merchantId, ExternalProduct p, Set<String> linkedOffers) {
        if (p.variants().size() != 1) {
            return null;
        }
        var sku = p.variants().getFirst().sku();
        if (sku == null || sku.isBlank()) {
            return null;
        }
        return listings.bySku(merchantId, sku.strip())
                .filter(l -> l instanceof ProductListing && !linkedOffers.contains(l.getId()))
                .map(ProductListing.class::cast)
                .orElse(null);
    }

    private void save(Connection connection, ExternalProduct product, ProductListing listing, String hash) {
        var variants = new ArrayList<VariantLink>();
        if (listing.getDetails().variants().isEmpty()) {
            var sku = Objects.requireNonNullElse(listing.sku(), "");
            product.variants().forEach(v -> variants.add(new VariantLink(v.id(), sku, v.stockRef())));
        } else {
            var skus = variantSkus(connection.provider(), product);
            product.variants()
                    .forEach(v -> variants.add(
                            new VariantLink(v.id(), Objects.requireNonNull(skus.get(v.id())), v.stockRef())));
        }
        links.save(
                new ProductLink(
                        connection.merchantId(),
                        connection.provider(),
                        product.id(),
                        listing.getId(),
                        hash,
                        product.updatedAt(),
                        null),
                variants,
                clock.instant());
    }

    // ── mapping ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The editor's details for a platform product. {@code base} (a draft being refreshed) keeps what the merchant
     * entered in Northline: category, attributes, bullets, fulfilment, compliance, returns, keywords.
     */
    static ProductDetails details(
            CommerceProvider provider, ExternalProduct p, @Nullable ProductDetails base, List<String> imageIds) {
        var single = p.variants().size() == 1;
        var first = p.variants().getFirst();
        var firstBarcode = first.barcode();
        var barcode = single && firstBarcode != null && Gtin.isValid(firstBarcode) ? firstBarcode : null;
        List<Variant> variants = List.of();
        var theme = VariantTheme.NONE;
        if (!single) {
            theme = theme(p);
            var skus = variantSkus(provider, p);
            var values = new HashSet<String>();
            var list = new ArrayList<Variant>();
            for (var v : p.variants()) {
                var value = uniqueValue(value(v), values);
                var code = v.barcode();
                var gtin = code != null && Gtin.isValid(code) ? code : null;
                list.add(new Variant(
                        null,
                        value,
                        Objects.requireNonNull(skus.get(v.id())),
                        gtin,
                        v.priceCents(),
                        Math.max(0, v.stock())));
            }
            variants = list;
        }
        var price = p.variants().stream()
                .mapToLong(ExternalVariant::priceCents)
                .min()
                .orElse(0);
        var stock = p.variants().stream().mapToInt(v -> Math.max(0, v.stock())).sum();
        var firstSku = first.sku();
        var sku = single && firstSku != null && !firstSku.isBlank() ? firstSku.strip() : null;
        var baseSku = base == null ? null : base.sku();
        if (baseSku != null) {
            sku = baseSku;
        }
        var vendor = p.vendor();
        return new ProductDetails(
                barcode == null ? IdentifierType.NONE : IdentifierType.GTIN,
                barcode,
                title(p.title()),
                vendor == null || vendor.isBlank() ? (base == null ? null : base.brand()) : vendor.strip(),
                base == null ? null : base.mpn(),
                base == null ? null : base.categoryId(),
                base == null ? Map.of() : base.attributes(),
                description(p.description()),
                base == null ? List.of() : base.bullets(),
                theme,
                variants,
                ImageSource.OWN,
                imageIds.isEmpty() && base != null ? base.ownImageIds() : imageIds,
                sku,
                price,
                base == null ? null : base.compareAtCents(),
                base == null ? null : base.costCents(),
                base == null ? ItemCondition.NEW : base.condition(),
                stock,
                base == null ? null : base.lowStockAt(),
                base == null ? List.of(Fulfilment.POOLED) : base.fulfilment(),
                base == null ? null : base.handlingTime(),
                base == null ? null : base.returnsPolicy(),
                base == null ? null : base.countryOfOrigin(),
                base != null && base.restrictedOk(),
                base != null && base.bilingualOk(),
                base != null && base.warranty(),
                base == null ? null : base.searchKeywords());
    }

    /** Northline variant SKU per platform variant id: the platform's SKU, else one derived from its id; unique. */
    static Map<String, String> variantSkus(CommerceProvider provider, ExternalProduct p) {
        var out = new LinkedHashMap<String, String>();
        var taken = new HashSet<String>();
        for (var v : p.variants()) {
            var own = v.sku();
            var base = own != null && !own.isBlank()
                    ? own.strip()
                    : provider.code().substring(0, 3).toUpperCase(Locale.ROOT) + "-" + tail(v.id());
            if (base.length() > ListingMessages.SKU_MAX) {
                base = base.substring(0, ListingMessages.SKU_MAX);
            }
            var sku = base;
            for (int n = 2; !taken.add(sku.toLowerCase(Locale.ROOT)); n++) {
                sku = base + "-" + n;
            }
            out.put(v.id(), sku);
        }
        return out;
    }

    private static String tail(String id) {
        var cut = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
        var t = id.substring(cut + 1);
        return t.length() > 12 ? t.substring(t.length() - 12) : t;
    }

    static VariantTheme theme(ExternalProduct p) {
        var names = p.variants().stream()
                .flatMap(v -> v.options().keySet().stream())
                .map(n -> n.strip().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        var size = names.contains("size") || names.contains("taille");
        var colour = names.contains("color") || names.contains("colour") || names.contains("couleur");
        if (size && colour) {
            return VariantTheme.SIZE_COLOUR;
        }
        if (colour) {
            return VariantTheme.COLOUR;
        }
        if (names.contains("length") || names.contains("longueur")) {
            return VariantTheme.LENGTH;
        }
        return VariantTheme.SIZE;
    }

    private static String value(ExternalVariant v) {
        var joined = String.join(
                " / ",
                v.options().values().stream()
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .toList());
        var value = joined.isEmpty() ? v.title().strip() : joined;
        return value.isEmpty() ? "Default" : value;
    }

    private static String uniqueValue(String value, Set<String> taken) {
        var candidate = value;
        for (int n = 2; !taken.add(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = value + " (" + n + ")";
        }
        return candidate;
    }

    static String title(String raw) {
        var t = raw.strip().replaceAll("\\s+", " ");
        if (t.length() <= ListingMessages.TITLE_MAX) {
            return t;
        }
        var cut = t.lastIndexOf(' ', ListingMessages.TITLE_MAX);
        return t.substring(0, cut > 40 ? cut : ListingMessages.TITLE_MAX).strip();
    }

    /** Plain text from the platform's (possibly HTML) description, at most 4000 characters. */
    static @Nullable String description(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        var text = TAGS.matcher(BLOCK_END.matcher(raw).replaceAll("\n")).replaceAll("");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n\n")
                .strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > ListingMessages.DESCRIPTION_MAX
                ? text.substring(0, ListingMessages.DESCRIPTION_MAX)
                : text;
    }

    /**
     * Downloads the product's images (main + up to 8) and stores those that meet the image standards (JPG/PNG, ≥ 1000
     * px, ≤ 15 MB); the others are skipped, and the editor then asks for a photo as usual.
     */
    private List<String> imageIds(String merchantId, ExternalProduct p) {
        var ids = new ArrayList<String>();
        for (var url : p.images()) {
            if (ids.size() == ListingMessages.IMAGES_MAX) {
                break;
            }
            var bytes = images.fetch(url).orElse(null);
            if (bytes == null || bytes.length == 0 || bytes.length > ListingMessages.IMAGE_MAX_BYTES) {
                continue;
            }
            var facts = inspector.inspect(bytes).orElse(null);
            if (facts == null || Math.max(facts.width(), facts.height()) < ListingMessages.IMAGE_MIN_PX) {
                log.debug("Skipped image {} of {}: below the image standards", url, p.id());
                continue;
            }
            ids.add(media.upload(merchantId, bytes).id());
        }
        return ids;
    }

    /** What the draft refresh compares: content and variant structure, not price or stock. */
    static String contentHash(ExternalProduct p) {
        var sb = new StringBuilder()
                .append(p.title())
                .append('\u0000')
                .append(Objects.requireNonNullElse(p.description(), ""))
                .append('\u0000')
                .append(Objects.requireNonNullElse(p.vendor(), ""));
        p.images().forEach(i -> sb.append('\u0000').append(i));
        for (var v : p.variants()) {
            sb.append('\u0001')
                    .append(v.id())
                    .append('|')
                    .append(Objects.requireNonNullElse(v.sku(), ""))
                    .append('|')
                    .append(Objects.requireNonNullElse(v.barcode(), ""))
                    .append('|')
                    .append(v.title())
                    .append('|')
                    .append(v.options());
        }
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
