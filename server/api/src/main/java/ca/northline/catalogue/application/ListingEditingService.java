package ca.northline.catalogue.application;

import static ca.northline.catalogue.domain.ListingMessages.IMAGE_UNKNOWN;
import static ca.northline.catalogue.domain.ListingMessages.SKU_TAKEN;

import ca.northline.catalogue.application.ListingView.ProductView;
import ca.northline.catalogue.application.ListingView.ServiceView;
import ca.northline.catalogue.domain.CatalogRecord;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceDetails;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Editor saves (drafts) for products and services, and the editor's read of one listing. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ListingEditingService implements EditProduct, EditService, ViewListing {

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
        media.attach(details.ownImageIds(), OFFER, listing.getId());
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
        media.attach(details.ownImageIds(), OFFER, listing.getId());
        revet.forEach(events::publishEvent);
        return productView(listing);
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
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        return details.sku() != null
                ? details
                : details.withSku(SkuGenerator.product(details.title(), s -> taken(merchantId, s)));
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
        return new ProductView(
                p,
                category,
                p.contentShared(),
                media.findAll(p.getDetails().ownImageIds()),
                visibility.visibleTo(p.getMerchantId(), media.findAll(p.catalogueImageIds())),
                p.completeness(category));
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
