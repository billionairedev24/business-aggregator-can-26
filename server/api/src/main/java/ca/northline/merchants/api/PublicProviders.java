package ca.northline.merchants.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Service businesses as customers see them (S-53 provider lists, S-54 provider page): active {@code provider} /
 * {@code both} businesses with a published business page. Only what the public page already shows.
 */
public interface PublicProviders {

    /**
     * @param tier {@code registered | trusted | master}
     * @param tagline the page's tagline in the reader's language (English fallback)
     * @param about the business's own description (Business step)
     * @param verifiedFacts verified checks the page may name: {@code kyc}, {@code insurance}, {@code site_visit},
     *     {@code licence:<REGISTRY>}
     * @param since when Northline approved the business (its creation when unknown)
     */
    record Provider(
            String merchantId,
            String slug,
            String displayName,
            String type,
            String tier,
            @Nullable String city,
            String brandColor,
            @Nullable String tagline,
            @Nullable String about,
            @Nullable String logoUrl,
            List<String> verifiedFacts,
            Instant since) {
        public Provider {
            verifiedFacts = List.copyOf(verifiedFacts);
        }
    }

    /** The published providers among {@code merchantIds}; others are absent. */
    List<Provider> published(Collection<String> merchantIds, String lang);

    Optional<Provider> bySlug(String slug, String lang);
}
