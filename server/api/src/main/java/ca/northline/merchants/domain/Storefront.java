package ca.northline.merchants.domain;

import ca.northline.merchants.api.StorefrontPublished;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * Business page / store / menu page of one merchant (aggregate: {@code merchants.storefronts} + ordered
 * {@code storefront_sections}). Enforces validation-rules.md › Storefront; the V016 triggers stay the last line of
 * defence.
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Storefront {
    public static final String SECTIONS = "sections";
    public static final String SECTIONS_SET = "Send every section of this page exactly once.";
    public static final String TAGLINE_FIELD = "tagline";
    public static final int TAGLINE_MAX = 80;
    public static final String TAGLINE_TOO_LONG = "At most 80 characters.";
    public static final String ANNOUNCEMENT_FIELD = "announcement";
    public static final int ANNOUNCEMENT_MAX = 120;
    public static final String ANNOUNCEMENT_TOO_LONG = "At most 120 characters.";

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    private final String merchantId;

    @ToString.Include
    private Slug slug;

    private PageKind pageKind;
    private BrandColor brandColor;
    private @Nullable String logoDocumentId;
    private @Nullable String tagline;
    private CtaLabel ctaLabel;
    private @Nullable String announcement;
    /** The custom domain and its verification / certificate lifecycle (S-31), if the owner entered one. */
    private @Nullable DomainClaim domainClaim;

    private @Nullable Instant publishedAt;
    private List<StorefrontSection> sections;
    private final Instant createdAt;
    private Instant updatedAt;

    /** A new page with the recommended sections, all on, in the recommended order. */
    public static Storefront create(String merchantId, MerchantType type, Slug slug, Instant at) {
        return new Storefront(
                Ids.next(),
                merchantId,
                slug,
                type.pageKind(),
                new BrandColor(BrandColor.DEFAULT),
                null,
                null,
                CtaLabel.defaultFor(type),
                null,
                null,
                null,
                defaults(type, at),
                at,
                at);
    }

    /** Sections in page order. */
    public List<StorefrontSection> sections() {
        return sections.stream()
                .sorted(Comparator.comparingInt(StorefrontSection::getPosition))
                .toList();
    }

    /** The business type changed during onboarding: new page kind, recommended sections again. */
    public void rebuildFor(MerchantType type, Instant at) {
        pageKind = type.pageKind();
        ctaLabel = CtaLabel.defaultFor(type);
        sections = defaults(type, at);
        updatedAt = at;
    }

    public void restyle(BrandColor color, Instant at) {
        brandColor = color;
        updatedAt = at;
    }

    public void retitle(@Nullable String newTagline, Instant at) {
        var value = blankToNull(newTagline);
        if (value != null && value.length() > TAGLINE_MAX) {
            throw RuleViolation.of(TAGLINE_FIELD, "length", TAGLINE_TOO_LONG);
        }
        tagline = value;
        updatedAt = at;
    }

    public void announce(@Nullable String text, Instant at) {
        var value = blankToNull(text);
        if (value != null && value.length() > ANNOUNCEMENT_MAX) {
            throw RuleViolation.of(ANNOUNCEMENT_FIELD, "length", ANNOUNCEMENT_TOO_LONG);
        }
        announcement = value;
        updatedAt = at;
    }

    public void relabel(CtaLabel label, Instant at) {
        ctaLabel = label;
        updatedAt = at;
    }

    public void useLogo(@Nullable String documentId, Instant at) {
        logoDocumentId = documentId;
        updatedAt = at;
    }

    public void moveTo(Slug newSlug, Instant at) {
        slug = newSlug;
        updatedAt = at;
    }

    public @Nullable CustomDomain getCustomDomain() {
        return domainClaim == null ? null : domainClaim.domain();
    }

    public CustomDomain.@Nullable Status getCustomDomainStatus() {
        return domainClaim == null ? null : domainClaim.status();
    }

    public @Nullable Instant getCustomDomainVerifiedAt() {
        return domainClaim == null ? null : domainClaim.verifiedAt();
    }

    /**
     * Sets or clears the custom domain. A new domain starts a new claim (pending, with {@code token} for its TXT record);
     * entering the same domain again changes nothing.
     *
     * @return whether the domain changed
     */
    public boolean connectDomain(@Nullable CustomDomain domain, String token, Instant at) {
        if (domain == null ? domainClaim == null : domain.equals(getCustomDomain())) {
            return false;
        }
        domainClaim = domain == null ? null : DomainClaim.start(domain, token, at);
        updatedAt = at;
        return true;
    }

    /** The next state of the same claim (a DNS check or an edge report). */
    public void advanceDomain(DomainClaim next) {
        var current = domainClaim;
        if (current == null
                || !current.domain().equals(next.domain())
                || !current.token().equals(next.token())) {
            throw new IllegalStateException(
                    "Not this storefront's claim: " + next.domain().value());
        }
        domainClaim = next;
    }

    /** One section in a reorder request. {@code settings} null keeps the stored settings. */
    public record SectionState(
            SectionKind kind, boolean enabled, @Nullable Map<String, Object> settings) {}

    /**
     * Reorder / toggle: {@code desired} is the full ordered list (validation-rules.md: "reorder is a single PATCH with
     * the full ordered list") and must name every section of this page exactly once.
     */
    public void arrange(List<SectionState> desired, Instant at) {
        var present = EnumSet.noneOf(SectionKind.class);
        sections.forEach(s -> present.add(s.getKind()));
        var asked = EnumSet.noneOf(SectionKind.class);
        desired.forEach(d -> asked.add(d.kind()));
        if (desired.size() != sections.size() || !asked.equals(present)) {
            throw RuleViolation.of(SECTIONS, "set", SECTIONS_SET);
        }
        var problems = new ArrayList<Violation>();
        for (int i = 0; i < desired.size(); i++) {
            var d = desired.get(i);
            if (d.kind().required() && !d.enabled()) {
                problems.add(new Violation(SECTIONS + "[" + i + "].enabled", "required", SectionKind.ALWAYS_ON));
            }
            if (d.settings() != null) {
                int at0 = i;
                d.kind()
                        .checkSettings(d.settings())
                        .ifPresent(m -> problems.add(new Violation(SECTIONS + "[" + at0 + "].settings", "range", m)));
            }
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        IntStream.range(0, desired.size()).forEach(i -> {
            var d = desired.get(i);
            var section = sections.stream()
                    .filter(s -> s.getKind() == d.kind())
                    .findFirst()
                    .orElseThrow();
            section.arrange(i, d.enabled(), d.settings(), at);
        });
        updatedAt = at;
    }

    /** Publishes the current page. Only approved businesses go live; a custom domain must be verified first. */
    public StorefrontPublished publish(String actorId, boolean merchantActive, Instant at) {
        if (!merchantActive) {
            throw new Conflict("not_approved", "Your page goes live once Northline approves your business.");
        }
        if (domainClaim != null && !domainClaim.status().proven()) {
            throw RuleViolation.of(CustomDomain.FIELD, "unverified", CustomDomain.UNVERIFIED);
        }
        publishedAt = at;
        updatedAt = at;
        var enabled = sections().stream()
                .filter(StorefrontSection::isEnabled)
                .map(s -> s.getKind().code())
                .toList();
        return new StorefrontPublished(
                Ids.next(),
                at,
                id,
                actorId,
                merchantId,
                slug.value(),
                pageKind.code(),
                enabled,
                domainClaim == null ? null : domainClaim.domain().value());
    }

    private static List<StorefrontSection> defaults(MerchantType type, Instant at) {
        var order = PageKind.defaultOrder(type);
        return IntStream.range(0, order.size())
                .mapToObj(i -> StorefrontSection.create(order.get(i), i, at))
                .toList();
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
