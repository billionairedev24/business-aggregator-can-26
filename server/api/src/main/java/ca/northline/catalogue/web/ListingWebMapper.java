package ca.northline.catalogue.web;

import ca.northline.catalogue.application.ListingSummary;
import ca.northline.catalogue.application.ListingView;
import ca.northline.catalogue.application.ListingView.ProductView;
import ca.northline.catalogue.application.ListingView.ServiceView;
import ca.northline.catalogue.application.LookupCatalogue;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Completeness;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.catalogue.domain.MediaAsset;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.web.ListingResponses.CatalogMatchResponse;
import ca.northline.catalogue.web.ListingResponses.CategoryResponse;
import ca.northline.catalogue.web.ListingResponses.CompletenessResponse;
import ca.northline.catalogue.web.ListingResponses.ListingDetail;
import ca.northline.catalogue.web.ListingResponses.ListingItem;
import ca.northline.catalogue.web.ListingResponses.MediaResponse;
import ca.northline.catalogue.web.ListingResponses.MissingField;
import ca.northline.catalogue.web.ListingResponses.ProductResponse;
import ca.northline.catalogue.web.ListingResponses.ServiceResponse;
import java.util.List;
import java.util.Locale;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Application read models → response records. {@code merchantId} (context) builds the image URLs. */
@Mapper
interface ListingWebMapper {

    default ListingDetail toDetail(ListingView view, @Context String merchantId) {
        return switch (view) {
            case ProductView p -> toResponse(p, merchantId);
            case ServiceView s -> toResponse(s);
        };
    }

    @Mapping(target = "id", source = "listing.id")
    @Mapping(target = "kind", constant = "product")
    @Mapping(target = "vetting", source = "listing.state.vetting")
    @Mapping(target = "status", source = "listing.state.status")
    @Mapping(target = "vettingFlags", source = "listing.state.flags")
    @Mapping(target = "revetReasons", source = "listing.state.revetReasons")
    @Mapping(target = "submittedAt", source = "listing.state.submittedAt")
    @Mapping(target = "updatedAt", source = "listing.state.updatedAt")
    @Mapping(target = "catalogRef", source = "listing.record.ref")
    @Mapping(target = "catalogTitle", source = "listing.record.title")
    @Mapping(target = "sharedRecord", expression = "java(!view.listing().getRecord().isSellerOwned())")
    @Mapping(target = "contentLocked", source = "listing.record.locked")
    @Mapping(target = "sellerCount", source = "listing.record.sellerCount")
    @Mapping(target = ".", source = "listing.details")
    @Mapping(target = "images", source = "ownImages")
    ProductResponse toResponse(ProductView view, @Context String merchantId);

    @Mapping(target = "id", source = "listing.id")
    @Mapping(target = "kind", constant = "service")
    @Mapping(target = "vetting", source = "listing.state.vetting")
    @Mapping(target = "status", source = "listing.state.status")
    @Mapping(target = "vettingFlags", source = "listing.state.flags")
    @Mapping(target = "revetReasons", source = "listing.state.revetReasons")
    @Mapping(target = "submittedAt", source = "listing.state.submittedAt")
    @Mapping(target = "updatedAt", source = "listing.state.updatedAt")
    @Mapping(target = ".", source = "listing.details")
    ServiceResponse toResponse(ServiceView view);

    default CompletenessResponse completeness(Completeness c) {
        return new CompletenessResponse(
                c.percent(),
                c.done(),
                c.total(),
                c.missing().stream()
                        .map(v -> new MissingField(v.field(), v.message()))
                        .toList());
    }

    default MediaResponse media(MediaAsset m, @Context String merchantId) {
        return new MediaResponse(
                m.id(),
                "/api/v1/merchants/%s/media/%s".formatted(merchantId, m.id()),
                m.width(),
                m.height(),
                m.onWhite());
    }

    List<MediaResponse> media(List<MediaAsset> media, @Context String merchantId);

    @Mapping(target = "meta", expression = "java(meta(summary, locale))")
    @Mapping(target = "vettingFlags", source = "flags")
    ListingItem toItem(ListingSummary summary, @Context Locale locale);

    List<ListingItem> toItems(List<ListingSummary> summaries, @Context Locale locale);

    /**
     * Second line under the name: services "60 min · instant book" (or "on request"), products the category.
     */
    default String meta(ListingSummary s, Locale locale) {
        var fr = "fr".equals(locale.getLanguage());
        if (s.kind() == ListingKind.PRODUCT) {
            return s.categoryName() == null ? "" : s.categoryName();
        }
        var duration = s.durationMin() == null ? "" : s.durationMin() + " min · ";
        var price = s.pricingMode() == PricingMode.QUOTE ? (fr ? "sur devis · " : "quote · ") : "";
        return price
                + duration
                + (s.instantBook()
                        ? (fr ? "réservation instantanée" : "instant book")
                        : (fr ? "sur demande" : "on request"));
    }

    @Mapping(target = "attributes", source = "attributes")
    CategoryResponse toResponse(CategoryProfile category);

    List<CategoryResponse> toCategories(List<CategoryProfile> categories);

    @Mapping(target = ".", source = "record")
    @Mapping(target = "images", source = "images")
    CatalogMatchResponse toResponse(LookupCatalogue.Match match, @Context String merchantId);
}
