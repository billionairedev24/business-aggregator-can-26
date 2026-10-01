package ca.northline.catalogue.application;

import static ca.northline.catalogue.domain.ListingMessages.BUNDLE_ITEM_BUNDLE;
import static ca.northline.catalogue.domain.ListingMessages.BUNDLE_ITEM_UNKNOWN;
import static ca.northline.catalogue.domain.ListingMessages.BUNDLE_VARIANT_REQUIRED;
import static ca.northline.catalogue.domain.ListingMessages.IMAGE_UNKNOWN;
import static ca.northline.catalogue.domain.ListingMessages.SKU_TAKEN;

import ca.northline.catalogue.application.ListingRepository.BundleComponent;
import ca.northline.catalogue.application.ListingRepository.VariantFact;
import ca.northline.catalogue.application.ListingView.BundleLine;
import ca.northline.catalogue.application.ListingView.ProductView;
import ca.northline.catalogue.application.ListingView.ServiceView;
import ca.northline.catalogue.domain.CatalogRecord;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.ListingMessages;
import ca.northline.catalogue.domain.OfferType;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ProductDetails.BundleItem;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceDetails;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Editor saves (drafts) for products and services, and the editor's read of one listing. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ListingEditingService implements EditProduct, EditService, ViewListing, QuickUpdateListing {

    static final String OFFER = "offer";
    static final String CATALOG_PRODUCT = "catalog_product";

    private final ListingRepository listings;
    private final CatalogRecords records;
    private final CategoryCatalog categories;
    private final MediaRepository media;
    private final MediaVisibility visibility;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public ListingView view(String merchantId, String listingId) {
        return switch (listings.find(merchantId, listingId).orElseThrow(() -> new NotFound("listing", listingId))) {
            case ProductListing p -> productView(p);
            case ServiceListing s -> serviceView(s);
        };
    }

    // ── products ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ProductView create(EditProduct.Command command) {
        var details = checked(command.merchantId(), null, command.details());
        var record = resolveRecord(command.merchantId(), details, null);
        var listing = ProductListing.create(
                Ids.next(),
                command.merchantId(),
                effective(command.merchantId(), details, record),
                record,
                clock.instant());
        listings.save(listing);
        media.attach(details.allOwnImageIds(), OFFER, listing.getId());
        return productView(listing);
    }

    @Override
    @Transactional
    public ProductView update(EditProduct.Command command) {
        var id = Objects.requireNonNull(command.listingId());
        var listing = listings.product(command.merchantId(), id).orElseThrow(() -> new NotFound("listing", id));
        var details = checked(command.merchantId(), id, command.details());
        var record = resolveRecord(command.merchantId(), details, listing.getRecord());
        var revet = listing.revise(
                effective(command.merchantId(), details, record), record, command.actorId(), clock.instant());
        listings.save(listing);
        media.attach(details.allOwnImageIds(), OFFER, listing.getId());
        revet.forEach(events::publishEvent);
        return productView(listing);
    }

    @Override
    @Transactional
    public ListingView update(QuickUpdateListing.Command command) {
        var listing = listings.find(command.merchantId(), command.listingId())
                .orElseThrow(() -> new NotFound("listing", command.listingId()));
        var price = command.priceCents();
        if (price != null && (price <= 0 || price > ListingMessages.PRICE_MAX_CENTS)) {
            throw RuleViolation.of("priceCents", "range", ListingMessages.PRICE_POSITIVE);
        }
        var stock = command.stock();
        if (stock != null && stock < 0) {
            throw RuleViolation.of("stock", "range", ListingMessages.STOCK_NEGATIVE);
        }
        var now = clock.instant();
        var events = switch (listing) {
            case ProductListing p -> {
                if (!p.getDetails().variants().isEmpty()) {
                    throw RuleViolation.of("priceCents", "variants", ListingMessages.QUICK_UPDATE_VARIANTS);
                }
                if (p.getDetails().isBundle() && stock != null) {
                    throw RuleViolation.of("stock", "bundle", ListingMessages.QUICK_UPDATE_BUNDLE_STOCK);
                }
                var revet = p.restock(
                        price == null ? p.getDetails().priceCents() : price,
                        stock == null ? p.getDetails().stock() : stock,
                        command.actorId(),
                        now);
                listings.save(p);
                yield revet;
            }
            case ServiceListing s -> {
                if (stock != null) {
                    throw RuleViolation.of("stock", "service", ListingMessages.QUICK_UPDATE_SERVICE_STOCK);
                }
                var revet = s.reprice(price == null ? s.getDetails().priceCents() : price, command.actorId(), now);
                listings.save(s);
                yield revet;
            }
        };
        events.forEach(this.events::publishEvent);
        return view(command.merchantId(), command.listingId());
    }

    /** Validates a save and fills in a SKU when there is none. */
    private ProductDetails checked(String merchantId, @Nullable String listingId, ProductDetails details) {
        var category = profile(details.categoryId());
        var problems = new ArrayList<>(details.validate(category));
        skuProblem(merchantId, details.sku(), listingId).ifPresent(problems::add);
        var own = media.findAll(details.ownImageIds());
        if (own.size() != details.ownImageIds().size()
                || own.stream().anyMatch(m -> !merchantId.equals(m.merchantId()))) {
            problems.add(new Violation("images", "unknown", IMAGE_UNKNOWN));
        }
        for (int i = 0; i < details.variants().size(); i++) { // S-65: a variant's own images
            var ids = details.variants().get(i).imageIds();
            var found = media.findAll(ids);
            if (found.size() != ids.size() || found.stream().anyMatch(m -> !merchantId.equals(m.merchantId()))) {
                problems.add(new Violation("variants[" + i + "].imageIds", "unknown", IMAGE_UNKNOWN));
            }
        }
        problems.addAll(bundleProblems(merchantId, listingId, details));
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        return details.sku() != null
                ? details
                : details.withSku(SkuGenerator.product(details.title(), s -> taken(merchantId, s)));
    }

    /**
     * S-65: every bundle item is one of the business's own products (not a bundle, not the bundle itself), of one of
     * its variants when it has some. Drafts may hold unapproved items; submitting needs them approved
     * ({@code ListingLifecycleService}).
     */
    private List<Violation> bundleProblems(String merchantId, @Nullable String listingId, ProductDetails details) {
        var items = details.bundleItems();
        if (items.isEmpty()) {
            return List.of();
        }
        var found = components(merchantId, items);
        var out = new ArrayList<Violation>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var at = "bundleItems[" + i + "].";
            var c = found.get(item.offerId());
            if (c == null || item.offerId().equals(listingId)) {
                out.add(new Violation(at + "offerId", "unknown", BUNDLE_ITEM_UNKNOWN));
            } else if (c.type() == OfferType.BUNDLE) {
                out.add(new Violation(at + "offerId", "bundle", BUNDLE_ITEM_BUNDLE));
            } else if (c.variants().isEmpty()
                    ? item.variantId() != null
                    : c.variants().stream().noneMatch(v -> v.id().equals(item.variantId()))) {
                out.add(new Violation(at + "variantId", "required", BUNDLE_VARIANT_REQUIRED));
            }
        }
        return out;
    }

    private Map<String, BundleComponent> components(String merchantId, List<BundleItem> items) {
        return listings
                .bundleComponents(
                        merchantId,
                        items.stream().map(BundleItem::offerId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(BundleComponent::offerId, c -> c));
    }

    /**
     * The editor's lines of a bundle and how many whole bundles the items' stock allows (0 when it has no items).
     */
    List<BundleLine> bundleLines(ProductListing p) {
        var items = p.getDetails().bundleItems();
        if (items.isEmpty()) {
            return List.of();
        }
        var found = components(p.getMerchantId(), items);
        return items.stream()
                .map(item -> {
                    var c = found.get(item.offerId());
                    if (c == null) {
                        return new BundleLine(item.offerId(), item.variantId(), item.qty(), "", null, 0, 0, 0);
                    }
                    var variant = c.variants().stream()
                            .filter(v -> v.id().equals(item.variantId()))
                            .findFirst();
                    var stock = variant.map(VariantFact::stock).orElse(c.stock());
                    return new BundleLine(
                            item.offerId(),
                            item.variantId(),
                            item.qty(),
                            c.name(),
                            variant.map(VariantFact::value).orElse(null),
                            variant.map(VariantFact::priceCents).orElse(c.priceCents()),
                            stock,
                            Math.max(0, stock) / item.qty());
                })
                .toList();
    }

    /**
     * The catalogue record behind the offer: the shared record for the GTIN (created when the GTIN is new — the first
     * seller supplies content and images), or the merchant's own record for goods without a GTIN.
     */
    CatalogRecord resolveRecord(String merchantId, ProductDetails details, @Nullable CatalogRecord current) {
        var gtin = details.gtin();
        if (gtin != null) {
            var existing = records.byGtin(gtin);
            if (existing.isPresent()) {
                var record = existing.get();
                if (!record.editableBy(merchantId)) {
                    return record;
                }
                var updated = record.withContent(details);
                records.update(updated);
                return updated;
            }
            var created = newRecord(details, null, merchantId);
            records.insert(created);
            return created;
        }
        if (current != null && merchantId.equals(current.ownerMerchantId())) {
            var updated = current.withContent(details);
            records.update(updated);
            return updated;
        }
        var own = newRecord(details, merchantId, merchantId);
        records.insert(own);
        return own;
    }

    private CatalogRecord newRecord(ProductDetails d, @Nullable String owner, String createdBy) {
        return new CatalogRecord(
                Ids.next(),
                records.nextRef(),
                d.gtin(),
                d.identifierType(),
                d.brand(),
                d.title(),
                d.mpn(),
                d.categoryId(),
                d.attributes(),
                d.description(),
                d.bullets(),
                d.ownImageIds(),
                owner,
                createdBy,
                false,
                0);
    }

    private static ProductDetails effective(String merchantId, ProductDetails details, CatalogRecord record) {
        return record.editableBy(merchantId) ? details : details.withContentOf(record);
    }

    ProductView productView(ProductListing p) {
        var category = profile(p.categoryId());
        var lines = bundleLines(p);
        return new ProductView(
                p,
                category,
                p.contentShared(),
                media.findAll(p.getDetails().ownImageIds()),
                visibility.visibleTo(p.getMerchantId(), media.findAll(p.catalogueImageIds())),
                p.completeness(category),
                media.findAll(p.getDetails().variants().stream()
                        .flatMap(v -> v.imageIds().stream())
                        .distinct()
                        .toList()),
                lines,
                p.getDetails().isBundle()
                        ? lines.stream().mapToInt(BundleLine::available).min().orElse(0)
                        : p.getDetails().stock());
    }

    // ── services ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ServiceView create(EditService.Command command) {
        var details = checked(command.merchantId(), null, command.details());
        var listing = ServiceListing.create(Ids.next(), command.merchantId(), details, clock.instant());
        listings.save(listing);
        return serviceView(listing);
    }

    @Override
    @Transactional
    public ServiceView update(EditService.Command command) {
        var id = Objects.requireNonNull(command.listingId());
        var listing = listings.service(command.merchantId(), id).orElseThrow(() -> new NotFound("listing", id));
        var revet = listing.revise(
                checked(command.merchantId(), id, command.details()), command.actorId(), clock.instant());
        listings.save(listing);
        revet.forEach(events::publishEvent);
        return serviceView(listing);
    }

    private ServiceDetails checked(String merchantId, @Nullable String listingId, ServiceDetails details) {
        var problems = new ArrayList<>(details.validate(profile(details.categoryId())));
        skuProblem(merchantId, details.sku(), listingId).ifPresent(problems::add);
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        return details.sku() != null
                ? details
                : details.withSku(SkuGenerator.service(details.name(), s -> taken(merchantId, s)));
    }

    ServiceView serviceView(ServiceListing s) {
        var category = profile(s.categoryId());
        return new ServiceView(s, category, s.completeness(category));
    }

    // ── shared ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private Optional<Violation> skuProblem(String merchantId, @Nullable String sku, @Nullable String listingId) {
        return sku != null && listings.skuTaken(merchantId, sku, listingId)
                ? Optional.of(new Violation("sku", "taken", SKU_TAKEN))
                : Optional.empty();
    }

    private boolean taken(String merchantId, String sku) {
        return listings.skuTaken(merchantId, sku, null);
    }

    private @Nullable CategoryProfile profile(@Nullable String categoryId) {
        return categoryId == null ? null : categories.profile(categoryId).orElse(null);
    }
}
