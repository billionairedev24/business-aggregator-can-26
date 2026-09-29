package ca.northline.merchants.application;

import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.Slug;
import ca.northline.merchants.domain.Storefront;
import java.util.Optional;

/** Outbound port: {@code merchants.storefronts} + {@code storefront_sections}. */
public interface StorefrontRepository {
    Optional<Storefront> findByMerchant(String merchantId);

    Optional<Storefront> findBySlug(String slug);

    void insert(Storefront storefront);

    /** Updates the storefront row and its sections (positions rewritten without breaking {@code ux_section_position}). */
    void save(Storefront storefront);

    /** Replaces all sections (page kind changed). */
    void replaceSections(Storefront storefront);

    boolean slugTaken(Slug slug, String exceptStorefrontId);

    boolean domainTaken(CustomDomain domain, String exceptStorefrontId);
}
