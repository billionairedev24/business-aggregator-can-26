package ca.northline.merchants.web;

import ca.northline.merchants.application.BrowseTaxonomy.TaxonomyView;
import ca.northline.merchants.application.OnboardingView;
import ca.northline.merchants.application.Taxonomy;
import ca.northline.merchants.domain.BusinessProfile;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Principal;
import ca.northline.merchants.domain.SelectedCategory;
import ca.northline.merchants.domain.Verification;
import ca.northline.merchants.web.OnboardingRequests.PrincipalRequest;
import ca.northline.merchants.web.OnboardingRequests.ProfileRequest;
import ca.northline.merchants.web.OnboardingResponses.BusinessResponse;
import ca.northline.merchants.web.OnboardingResponses.CategoryResponse;
import ca.northline.merchants.web.OnboardingResponses.CheckResponse;
import ca.northline.merchants.web.OnboardingResponses.DocumentResponse;
import ca.northline.merchants.web.OnboardingResponses.GroupResponse;
import ca.northline.merchants.web.OnboardingResponses.ItemResponse;
import ca.northline.merchants.web.OnboardingResponses.OnboardingResponse;
import ca.northline.merchants.web.OnboardingResponses.PrincipalResponse;
import ca.northline.merchants.web.OnboardingResponses.TaxonomyResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Context;
import org.mapstruct.Mapper;

@Mapper
interface OnboardingWebMapper {

    ZoneId EDMONTON = ZoneId.of("America/Edmonton");

    default OnboardingResponse toResponse(OnboardingView view) {
        var a = view.application();
        var checks = view.verifications().stream()
                .map(v -> toCheck(v, view.documents()))
                .toList();
        return new OnboardingResponse(
                a.getId(),
                a.getType(),
                a.getStatus(),
                a.getStep(),
                a.getProvince(),
                a.getWorkEmail(),
                a.getBusinessTermsAcceptedAt() != null,
                a.getDisplayName(),
                a.getCity(),
                a.businessSaved() ? toBusiness(a, view.documents()) : null,
                checks,
                (int) view.verifications().stream()
                        .filter(v -> v.getStatus().complete())
                        .count(),
                a.getSubmittedAt(),
                a.getApprovedAt());
    }

    default BusinessResponse toBusiness(MerchantApplication a, Map<String, Document> documents) {
        var legalDocs = a.getLegalDetails().entrySet().stream()
                .filter(e -> e.getKey().endsWith("_doc") && e.getValue() instanceof String)
                .map(e -> documents.get(String.valueOf(e.getValue())))
                .filter(Objects::nonNull)
                .map(this::toDocument)
                .toList();
        return new BusinessResponse(
                a.getDisplayName(),
                a.getLegalName(),
                a.getStructure(),
                a.getGstNumber(),
                a.getLegalDetails(),
                toPrincipals(a.getPrincipals()),
                toCategories(a.getCategories()),
                a.getProfile(),
                legalDocs);
    }

    default CheckResponse toCheck(Verification v, Map<String, Document> documents) {
        var docId = v.getDocumentId();
        var doc = docId == null ? null : documents.get(docId);
        return new CheckResponse(
                v.getId(),
                v.getKey(),
                v.getType(),
                v.kind().action(),
                v.getRegistry(),
                v.getStatus(),
                v.getReference(),
                doc == null ? null : toDocument(doc),
                localDate(v.getExpiresAt()),
                v.getUpdatedAt());
    }

    DocumentResponse toDocument(Document document);

    PrincipalResponse toPrincipal(Principal principal);

    List<PrincipalResponse> toPrincipals(List<Principal> principals);

    CategoryResponse toCategory(SelectedCategory category);

    List<CategoryResponse> toCategories(List<SelectedCategory> categories);

    default Principal fromRequest(PrincipalRequest request) {
        return new Principal(
                Objects.requireNonNullElse(request.legalName(), ""), request.role(), request.ownershipPct());
    }

    List<Principal> fromRequests(List<PrincipalRequest> requests);

    BusinessProfile toProfile(ProfileRequest request);

    default TaxonomyResponse toResponse(TaxonomyView view, @Context Locale locale) {
        var lang = locale.getLanguage().equals("fr") ? "fr" : "en";
        return new TaxonomyResponse(
                view.type(),
                view.limit(),
                view.groups().stream()
                        .map(g -> new GroupResponse(
                                g.id(),
                                g.name(lang),
                                g.note(),
                                g.items().stream().map(i -> toItem(i, lang)).toList()))
                        .toList());
    }

    default ItemResponse toItem(Taxonomy.Item item, String lang) {
        return new ItemResponse(item.id(), item.name(lang), item.regulator());
    }

    default @Nullable LocalDate localDate(@Nullable Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, EDMONTON);
    }
}
