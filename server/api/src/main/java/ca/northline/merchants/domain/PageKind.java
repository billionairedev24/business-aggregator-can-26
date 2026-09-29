package ca.northline.merchants.domain;

import static ca.northline.merchants.domain.SectionKind.ABOUT;
import static ca.northline.merchants.domain.SectionKind.AREA;
import static ca.northline.merchants.domain.SectionKind.CATALOGUE;
import static ca.northline.merchants.domain.SectionKind.CTA;
import static ca.northline.merchants.domain.SectionKind.DELIVERY;
import static ca.northline.merchants.domain.SectionKind.FAQ;
import static ca.northline.merchants.domain.SectionKind.FEATURED;
import static ca.northline.merchants.domain.SectionKind.FULFIL;
import static ca.northline.merchants.domain.SectionKind.GALLERY;
import static ca.northline.merchants.domain.SectionKind.HERO;
import static ca.northline.merchants.domain.SectionKind.HOURS;
import static ca.northline.merchants.domain.SectionKind.MENU;
import static ca.northline.merchants.domain.SectionKind.PERMIT;
import static ca.northline.merchants.domain.SectionKind.POLICIES;
import static ca.northline.merchants.domain.SectionKind.REVIEWS;
import static ca.northline.merchants.domain.SectionKind.SERVICES;

import ca.northline.shared.CodedEnum;
import java.util.List;
import java.util.Set;

/**
 * {@code merchants.storefronts.page_kind} with the allowed section kinds (same sets as trigger
 * {@code trg_section_kind}) and recommended orders of docs/spec/storefront-sections.json. {@code StorefrontSpecTest}
 * checks both against the spec file.
 */
public enum PageKind implements CodedEnum {
    BUSINESS_PAGE(
            Set.of(HERO, CTA, ABOUT, SERVICES, REVIEWS, AREA, GALLERY, FAQ, FEATURED, CATALOGUE, DELIVERY, POLICIES),
            List.of(HERO, ABOUT, SERVICES, REVIEWS, AREA, GALLERY, FAQ, CTA)),
    STORE(
            Set.of(HERO, CTA, ABOUT, FEATURED, CATALOGUE, DELIVERY, REVIEWS, POLICIES),
            List.of(HERO, ABOUT, FEATURED, CATALOGUE, DELIVERY, REVIEWS, POLICIES, CTA)),
    MENU_PAGE(
            Set.of(HERO, CTA, ABOUT, MENU, HOURS, FULFIL, REVIEWS, PERMIT),
            List.of(HERO, ABOUT, MENU, HOURS, FULFIL, REVIEWS, PERMIT, CTA));

    /** {@code both_default_order}: provider + seller sections on one business page. */
    public static final List<SectionKind> BOTH_DEFAULT_ORDER =
            List.of(HERO, ABOUT, SERVICES, FEATURED, CATALOGUE, REVIEWS, AREA, POLICIES, CTA);

    @SuppressWarnings("ImmutableEnumChecker") // Set.of / List.of are unmodifiable
    private final Set<SectionKind> allowed;

    @SuppressWarnings("ImmutableEnumChecker")
    private final List<SectionKind> defaultOrder;

    PageKind(Set<SectionKind> allowed, List<SectionKind> defaultOrder) {
        this.allowed = allowed;
        this.defaultOrder = defaultOrder;
    }

    public Set<SectionKind> allowed() {
        return allowed;
    }

    /** The recommended order ("Reset to recommended order") for a merchant of {@code type}. */
    public static List<SectionKind> defaultOrder(MerchantType type) {
        return type == MerchantType.BOTH ? BOTH_DEFAULT_ORDER : type.pageKind().defaultOrder;
    }
}
