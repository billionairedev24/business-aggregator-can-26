package ca.northline.merchants.web;

import ca.northline.merchants.application.StorefrontUseCases.StorefrontView;
import ca.northline.merchants.domain.BrandColor;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Storefront;
import ca.northline.merchants.domain.StorefrontSection;
import ca.northline.merchants.domain.Verification;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.merchants.web.StorefrontDtos.BusinessCard;
import ca.northline.merchants.web.StorefrontDtos.LogoResponse;
import ca.northline.merchants.web.StorefrontDtos.PublicSection;
import ca.northline.merchants.web.StorefrontDtos.PublicStorefrontResponse;
import ca.northline.merchants.web.StorefrontDtos.SectionRequest;
import ca.northline.merchants.web.StorefrontDtos.SectionResponse;
import ca.northline.merchants.web.StorefrontDtos.StorefrontResponse;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.mapstruct.Mapper;

@Mapper
interface StorefrontWebMapper {

    String PUBLIC_HOST = "northline.ca/";
    Set<CheckKind> FACTS =
            Set.of(CheckKind.KYC, CheckKind.LICENCE, CheckKind.INSURANCE, CheckKind.AHS_PERMIT, CheckKind.SITE_VISIT);

    default StorefrontResponse toResponse(StorefrontView view) {
        var s = view.storefront();
        var logo = view.logo();
        return new StorefrontResponse(
                s.getId(),
                s.getMerchantId(),
                s.getSlug().value(),
                PUBLIC_HOST + s.getSlug().value(),
                s.getPageKind(),
                s.getBrandColor().hex(),
                Math.round(BrandColor.contrastWithWhite(s.getBrandColor().hex()) * 10) / 10.0,
                logo == null
                        ? null
                        : new LogoResponse(
                                logo.id(),
                                logo.fileName(),
                                "/api/v1/merchants/%s/documents/%s".formatted(s.getMerchantId(), logo.id())),
                s.getTagline(),
                s.getCtaLabel(),
                s.getAnnouncement(),
                domain(s.getCustomDomain()),
                s.getCustomDomainStatus(),
                s.getPublishedAt(),
                s.sections().stream().map(this::toSection).toList(),
                card(view.merchant(), view.verifications()));
    }

    default PublicStorefrontResponse toPublic(StorefrontView view) {
        var s = view.storefront();
        return new PublicStorefrontResponse(
                s.getSlug().value(),
                PUBLIC_HOST + s.getSlug().value(),
                s.getPageKind(),
                s.getBrandColor().hex(),
                view.logo() == null
                        ? null
                        : "/api/v1/storefronts/%s/logo".formatted(s.getSlug().value()),
                s.getTagline(),
                s.getCtaLabel(),
                s.getAnnouncement(),
                s.getCustomDomainStatus() == CustomDomain.Status.VERIFIED ? domain(s.getCustomDomain()) : null,
                Objects.requireNonNull(s.getPublishedAt()),
                s.sections().stream()
                        .filter(StorefrontSection::isEnabled)
                        .map(x -> new PublicSection(x.getKind(), x.getSettings()))
                        .toList(),
                card(view.merchant(), view.verifications()));
    }

    default SectionResponse toSection(StorefrontSection s) {
        return new SectionResponse(
                s.getId(),
                s.getKind(),
                s.getPosition(),
                s.isEnabled(),
                s.getKind().required(),
                s.getSettings());
    }

    default BusinessCard card(MerchantApplication m, List<Verification> checks) {
        var p = m.getProfile();
        return new BusinessCard(
                m.getDisplayName(),
                m.getType(),
                m.getTier(),
                m.getStatus(),
                m.getCity(),
                p.description(),
                p.serviceArea(),
                p.sameDayCutoff(),
                p.fulfilment(),
                p.cuisines(),
                checks.stream()
                        .filter(v -> v.getStatus() == VerificationStatus.VERIFIED && FACTS.contains(v.kind()))
                        .map(Verification::getKey)
                        .toList());
    }

    default Storefront.SectionState toState(SectionRequest request) {
        return new Storefront.SectionState(request.kind(), request.enabled(), request.settings());
    }

    List<Storefront.SectionState> toStates(List<SectionRequest> requests);

    default @org.jspecify.annotations.Nullable String domain(@org.jspecify.annotations.Nullable CustomDomain domain) {
        return domain == null ? null : domain.value();
    }
}
