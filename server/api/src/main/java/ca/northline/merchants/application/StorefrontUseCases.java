package ca.northline.merchants.application;

import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Storefront;
import ca.northline.merchants.domain.Verification;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Page builder use cases (onboarding step 5 and Studio → Business page / Store / Menu page). */
public final class StorefrontUseCases {
    private StorefrontUseCases() {}

    /**
     * Read model: the page, the business it belongs to (for the preview), its logo / verified facts, and what the owner
     * must put in DNS for the custom domain.
     *
     * @param domainTarget where custom domains point: {@code pages.<zone>}
     * @param domainSetup the records and state of the custom domain, when there is one
     */
    public record StorefrontView(
            Storefront storefront,
            MerchantApplication merchant,
            @Nullable Document logo,
            List<Verification> verifications,
            String domainTarget,
            @Nullable DomainSetup domainSetup) {
        public StorefrontView {
            verifications = List.copyOf(verifications);
        }
    }

    /**
     * DNS instructions for a custom domain (S-31, design 02: "Point a CNAME at pages.northline.ca; we issue the
     * certificate").
     *
     * @param apex a root domain: no CNAME possible; ALIAS/ANAME/flattening to {@code target}, or A records
     * @param records what to add at the DNS host: {@code CNAME} (or {@code ALIAS} / {@code A} for an apex) and the
     *     ownership {@code TXT}
     * @param graceEndsAt while the records don't point at us: when the domain stops being served
     */
    public record DomainSetup(
            String target,
            boolean apex,
            List<DnsRecord> records,
            @Nullable Instant graceEndsAt) {
        public DomainSetup {
            records = List.copyOf(records);
        }
    }

    public record DnsRecord(String type, String name, String value) {}

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

    /** "Check now": asks DNS about the custom domain at once (at most every {@code DOMAINS_CHECK_COOLDOWN}). */
    public interface VerifyCustomDomain {
        StorefrontView verify(String merchantId);
    }

    /** LOCAL ONLY ("Simulate DNS records →"): publishes the domain's records in the in-memory zone, then checks. */
    public interface SimulateDomainRecords {
        StorefrontView simulate(String merchantId);
    }

    /**
     * Public: the page a live custom domain serves (the consumer app resolves the {@code Host} header with it).
     * Hosts that are not a live custom domain are not found.
     */
    public interface ResolveStorefrontHost {
        StorefrontView byHost(String host);
    }

    /** The scheduler's work (S-31); each returns how many domains changed. */
    public interface CustomDomainJobs {
        /** DNS checks that are due (pending domains, periodic re-checks of proven ones, failed ones). */
        int checkDue();

        /** Makes the edge serve the domains that may serve, and records what it reports. */
        int reconcileEdge();
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
