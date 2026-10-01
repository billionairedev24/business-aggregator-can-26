package ca.northline.search.web;

import ca.northline.search.application.SearchResults;
import ca.northline.search.application.Suggestion;
import ca.northline.search.web.SearchDtos.CategoryRef;
import ca.northline.search.web.SearchDtos.MerchantRef;
import ca.northline.search.web.SearchDtos.ResultItem;
import ca.northline.search.web.SearchDtos.SearchResponse;
import ca.northline.search.web.SearchDtos.SuggestResponse;
import ca.northline.search.web.SearchDtos.SuggestionResponse;
import ca.northline.searchindex.ListingDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface SearchWebMapper {

    SearchResponse toResponse(SearchResults results);

    @Mapping(target = "id", source = "document.id")
    @Mapping(target = "kind", source = "document.kind")
    @Mapping(target = "name", source = "document.name")
    @Mapping(target = "description", source = "document.description")
    @Mapping(target = "merchant", source = "document")
    @Mapping(target = "category", source = "document")
    @Mapping(target = "priceCents", source = "document.priceCents")
    @Mapping(target = "pricingMode", source = "document.pricingMode")
    @Mapping(target = "rating", source = "document.rating")
    @Mapping(target = "reviewCount", source = "document.reviewCount")
    @Mapping(target = "trustTier", source = "document.trustTier")
    @Mapping(target = "instantBook", source = "document.instantBook")
    @Mapping(target = "fulfilment", source = "document.fulfilment")
    @Mapping(target = "prepMinutes", source = "document.prepMinutes")
    @Mapping(target = "dietary", source = "document.dietary")
    @Mapping(target = "allergens", source = "document.allergens")
    @Mapping(target = "imageKey", source = "document.imageKey")
    ResultItem toItem(SearchResults.Hit hit);

    @Mapping(target = "id", source = "merchantId")
    @Mapping(target = "name", source = "merchantName")
    @Mapping(target = "type", source = "merchantType")
    @Mapping(target = "slug", source = "merchantSlug")
    @Mapping(target = "tier", source = "trustTier")
    MerchantRef toMerchant(ListingDocument document);

    /** The leaf category: its id and the last name of its path. */
    default @Nullable CategoryRef toCategory(ListingDocument document) {
        var id = document.categoryId();
        if (id == null) {
            return null;
        }
        var names = document.categoryNames();
        return new CategoryRef(id, names.isEmpty() ? null : names.getLast());
    }

    SuggestionResponse toSuggestion(Suggestion suggestion);

    default SuggestResponse toSuggestResponse(List<Suggestion> suggestions) {
        return new SuggestResponse(suggestions.stream().map(this::toSuggestion).toList());
    }
}
