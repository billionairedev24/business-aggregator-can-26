package ca.northline.merchants.web;

import ca.northline.merchants.domain.CtaLabel;
import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.PageKind;
import ca.northline.merchants.domain.SectionKind;
import ca.northline.merchants.domain.Storefront;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Request/response bodies of the page builder API (see docs/DECISIONS.md › storefront API). */
final class StorefrontDtos {
    private StorefrontDtos() {}

    /**
     * {@code PATCH …/storefront}. Absent/null = unchanged; {@code ""} clears tagline, announcement, customDomain and
     * logoDocumentId.
     */
    record UpdateRequest(
            @Nullable String brandColor,
            @Nullable String tagline,
            @Nullable String ctaLabel,
            @Nullable String announcement,
            @Nullable String customDomain,
            @Nullable String slug,
            @Nullable String logoDocumentId) {}

    /** {@code PATCH …/storefront/sections}: the full ordered list. */
    record SectionsRequest(
            @NotNull(message = Storefront.SECTIONS_SET) @Valid
            List<SectionRequest> sections) {}

    record SectionRequest(
            @NotNull(message = Storefront.SECTIONS_SET) SectionKind kind,
            boolean enabled,
            @Nullable Map<String, Object> settings) {}

    /** The page as the builder edits it. */
    record StorefrontResponse(
            String id,
            String merchantId,
            String slug,
            String url,
            PageKind pageKind,
            String brandColor,
            double brandContrast,
            @Nullable LogoResponse logo,
            @Nullable String tagline,
            CtaLabel ctaLabel,
            @Nullable String announcement,
            @Nullable String customDomain,
            CustomDomain.@Nullable Status customDomainStatus,
            @Nullable Instant publishedAt,
            List<SectionResponse> sections,
            BusinessCard business) {}

    record LogoResponse(String id, String fileName, String url) {}

    record SectionResponse(
            String id,
            SectionKind kind,
            int position,
            boolean enabled,
            boolean required,
            Map<String, Object> settings) {}

    /**
     * What the preview needs about the business. {@code verifiedFacts}: {@code kyc}, {@code insurance},
     * {@code ahs_permit}, {@code site_visit}, {@code licence:<REGISTRY>} for every verified check.
     */
    record BusinessCard(
            String displayName,
            MerchantType type,
            @Nullable MerchantTier tier,
            MerchantStatus status,
            @Nullable String city,
            @Nullable String about,
            @Nullable String serviceArea,
            @Nullable String sameDayCutoff,
            List<String> fulfilment,
            List<String> cuisines,
            List<String> verifiedFacts) {}

    /** {@code GET /api/v1/storefronts/{slug}} — public, enabled sections only. */
    record PublicStorefrontResponse(
            String slug,
            String url,
            PageKind pageKind,
            String brandColor,
            @Nullable String logoUrl,
            @Nullable String tagline,
            CtaLabel ctaLabel,
            @Nullable String announcement,
            @Nullable String customDomain,
            Instant publishedAt,
            List<PublicSection> sections,
            BusinessCard business) {}

    record PublicSection(SectionKind kind, Map<String, Object> settings) {}
}
