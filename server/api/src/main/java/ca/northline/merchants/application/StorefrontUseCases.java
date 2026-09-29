package ca.northline.merchants.application;

import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Storefront;
import ca.northline.merchants.domain.Verification;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Page builder use cases (onboarding step 5 and Studio → Business page / Store / Menu page). */
public final class StorefrontUseCases {
    private StorefrontUseCases() {}

    /** Read model: the page, the business it belongs to (for the preview) and its logo / verified facts. */
    public record StorefrontView(
            Storefront storefront,
            MerchantApplication merchant,
            @Nullable Document logo,
            List<Verification> verifications) {
        public StorefrontView {
            verifications = List.copyOf(verifications);
        }
    }

    public interface ViewStorefront {
        StorefrontView view(String merchantId);
    }

    /** Creates the page with the recommended sections (idempotent: returns the existing one). */
    public interface CreateStorefront {
        StorefrontView create(String merchantId);
    }

    /**
     * Brand, logo, tagline, CTA label, announcement, custom domain, slug. {@code null} leaves a field unchanged; an
     * empty string clears the optional ones (tagline, announcement, custom domain, logo).
     */
    public interface UpdateStorefront {
        record Command(
                String merchantId,
                @Nullable String brandColor,
                @Nullable String tagline,
                @Nullable String ctaLabel,
                @Nullable String announcement,
                @Nullable String customDomain,
                @Nullable String slug,
                @Nullable String logoDocumentId) {}

        StorefrontView update(Command command);
    }

    /** Reorder / toggle sections: the full ordered list in one call. */
    public interface ArrangeSections {
        StorefrontView arrange(String merchantId, List<Storefront.SectionState> sections);
    }

    /** Re-checks the custom domain's CNAME. */
    public interface VerifyCustomDomain {
        StorefrontView verify(String merchantId);
    }

    /** Publishes the page ({@code storefront.published}). */
    public interface PublishStorefront {
        StorefrontView publish(String merchantId, String actorId);
    }

    /** Public read of a published page by slug (consumer web / app). */
    public interface ViewPublishedStorefront {
        StorefrontView bySlug(String slug);

        Documents.ReadDocument.Content logo(String slug);
    }
}
