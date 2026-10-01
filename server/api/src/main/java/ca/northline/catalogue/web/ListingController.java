package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.DELETE;
import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.BrowseListings;
import ca.northline.catalogue.application.EditProduct;
import ca.northline.catalogue.application.EditService;
import ca.northline.catalogue.application.ManageListing;
import ca.northline.catalogue.application.QuickUpdateListing;
import ca.northline.catalogue.application.ViewListing;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.catalogue.web.ListingResponses.ListingDetail;
import ca.northline.catalogue.web.ListingResponses.ListingItem;
import ca.northline.catalogue.web.ListingResponses.ProductResponse;
import ca.northline.catalogue.web.ListingResponses.ServiceResponse;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.CurrentUser;
import ca.northline.shared.security.PartnerAccess;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio listings: the table ({@code GET /listings}), the product and service editors, submit for vetting, publish /
 * hide, delete. Owners do everything, technicians everything but delete, bookkeepers only read.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class ListingController {

    private final BrowseListings browseListings;
    private final ViewListing viewListing;
    private final EditProduct editProduct;
    private final EditService editService;
    private final ManageListing manageListing;
    private final QuickUpdateListing quickUpdate;
    private final ListingWebMapper mapper;

    @GetMapping("/listings")
    @RequiresMerchant(VIEW)
    @PartnerAccess(
            PartnerAccess.READ) // S-30: partners (inventory/accounting sync) read the listings of their businesses
    ListResponse<ListingItem> list(
            @PathVariable String merchantId,
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(defaultValue = "500") int limit,
            Locale locale) {
        ListingKind k = null;
        if (kind != null && !kind.isBlank()) {
            try {
                k = CodedEnum.fromCode(ListingKind.class, kind);
            } catch (IllegalArgumentException ex) {
                throw RuleViolation.of("kind", "format", "Use service or product.");
            }
        }
        return new ListResponse<>(mapper.toItems(browseListings.list(merchantId, k, limit, locale), locale));
    }

    @GetMapping("/listings/{listingId}")
    @RequiresMerchant(VIEW)
    @PartnerAccess(PartnerAccess.READ)
    ListingDetail get(@PathVariable String merchantId, @PathVariable String listingId) {
        return mapper.toDetail(viewListing.view(merchantId, listingId), merchantId);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(EDIT)
    ProductResponse createProduct(
            @PathVariable String merchantId, @Valid @RequestBody ProductRequest body, CurrentMember member) {
        var command = new EditProduct.Command(merchantId, null, body.toDetails(), member.userId());
        return mapper.toResponse(editProduct.create(command), merchantId);
    }

    @PutMapping("/products/{listingId}")
    @RequiresMerchant(EDIT)
    ProductResponse updateProduct(
            @PathVariable String merchantId,
            @PathVariable String listingId,
            @Valid @RequestBody ProductRequest body,
            CurrentMember member) {
        var command = new EditProduct.Command(merchantId, listingId, body.toDetails(), member.userId());
        return mapper.toResponse(editProduct.update(command), merchantId);
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(EDIT)
    ServiceResponse createService(
            @PathVariable String merchantId, @Valid @RequestBody ServiceRequest body, CurrentMember member) {
        var command = new EditService.Command(merchantId, null, body.toDetails(), member.userId());
        return mapper.toResponse(editService.create(command));
    }

    @PutMapping("/services/{listingId}")
    @RequiresMerchant(EDIT)
    ServiceResponse updateService(
            @PathVariable String merchantId,
            @PathVariable String listingId,
            @Valid @RequestBody ServiceRequest body,
            CurrentMember member) {
        var command = new EditService.Command(merchantId, listingId, body.toDetails(), member.userId());
        return mapper.toResponse(editService.update(command));
    }

    /**
     * S-127: price and stock only (a product without variants; a service's price). Partners with {@code api.write}
     * may call it for their businesses (inventory sync).
     */
    @PatchMapping("/listings/{listingId}/price-stock")
    @RequiresMerchant(EDIT)
    @PartnerAccess(PartnerAccess.WRITE)
    ListingDetail updatePriceAndStock(
            @PathVariable String merchantId,
            @PathVariable String listingId,
            @Valid @RequestBody PriceStockRequest body,
            CurrentUser user) {
        return mapper.toDetail(
                quickUpdate.update(new QuickUpdateListing.Command(
                        merchantId, listingId, body.priceCents(), body.stock(), user.userId())),
                merchantId);
    }

    @PostMapping("/listings/{listingId}/submit")
    @RequiresMerchant(EDIT)
    ListingDetail submit(@PathVariable String merchantId, @PathVariable String listingId, CurrentMember member) {
        return mapper.toDetail(manageListing.submit(merchantId, listingId, member.userId()), merchantId);
    }

    @PostMapping("/listings/{listingId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresMerchant(EDIT)
    void publish(@PathVariable String merchantId, @PathVariable String listingId) {
        manageListing.publish(merchantId, listingId);
    }

    @PostMapping("/listings/{listingId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresMerchant(EDIT)
    void hide(@PathVariable String merchantId, @PathVariable String listingId) {
        manageListing.hide(merchantId, listingId);
    }

    @DeleteMapping("/listings/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresMerchant(DELETE)
    void delete(@PathVariable String merchantId, @PathVariable String listingId, CurrentMember member) {
        manageListing.delete(merchantId, listingId, member.userId());
    }
}
