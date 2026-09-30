package ca.northline.merchants.application;

import ca.northline.merchants.domain.CustomDomain;
import ca.northline.merchants.domain.DomainClaim;
import ca.northline.merchants.domain.Slug;
import ca.northline.merchants.domain.Storefront;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code merchants.storefronts} + {@code storefront_sections}. */
public interface StorefrontRepository {
    Optional<Storefront> findByMerchant(String merchantId);

    /** Same, with the row locked for the transaction (every writer of the custom domain locks first). */
    Optional<Storefront> lockByMerchant(String merchantId);

    Optional<Storefront> findBySlug(String slug);

    /** The page serving {@code host} as its live custom domain. */
    Optional<Storefront> findLiveByDomain(String host);

    void insert(Storefront storefront);

    /**
     * Updates the storefront row and its sections (positions rewritten without breaking {@code ux_section_position}).
     * The custom domain is not written here: {@link #saveClaim}.
     */
    void save(Storefront storefront);

    /** Replaces all sections (page kind changed). */
    void replaceSections(Storefront storefront);

    boolean slugTaken(Slug slug, String exceptStorefrontId);

    // ── custom domains (S-31) ──────────────────────────────────────────────────────────────────────────────────────

    /** A storefront's custom domain as the jobs see it. */
    record ClaimRow(
            String storefrontId, String merchantId, DomainClaim claim, boolean published, boolean merchantActive) {}

    /** The other storefront holding {@code domain}, locked. */
    Optional<ClaimRow> lockClaimOf(CustomDomain domain, String exceptStorefrontId);

    /** Domains whose DNS check is due, locked; rows another replica holds are skipped. */
    List<ClaimRow> lockDueClaims(Instant now, int limit);

    /** Every verified / issuing / live domain, locked (the edge reconciler). */
    List<ClaimRow> lockProvenClaims();

    /**
     * Writes a storefront's custom domain columns. With {@code expectedToken}, only while that claim is still the
     * storefront's (a merchant may have changed the domain meanwhile).
     *
     * @param claim null = no custom domain
     * @return whether the row was written
     */
    boolean saveClaim(String storefrontId, @Nullable String expectedToken, @Nullable DomainClaim claim);

    /** Storefronts whose last certificate request is at or after {@code since}. */
    int certificateRequestsSince(Instant since);

    /** Transaction-scoped lock so one api replica at a time reconciles the edge; false = another one is at it. */
    boolean tryEdgeLock();
}
