package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * {@code merchants.storefront_sections.kind} (docs/spec/storefront-sections.json {@code sections}). {@code hero} and
 * {@code cta} are required (always enabled, DB check {@code chk_always_on}). The settings limits come from the spec's
 * {@code settings} descriptions (gallery ≤ 12 media, FAQ ≤ 8 pairs, featured ≤ 6 offers).
 */
public enum SectionKind implements CodedEnum {
    HERO(true),
    CTA(true),
    ABOUT(false),
    SERVICES(false),
    REVIEWS(false),
    AREA(false),
    GALLERY(false),
    FAQ(false),
    FEATURED(false),
    CATALOGUE(false),
    DELIVERY(false),
    POLICIES(false),
    MENU(false),
    HOURS(false),
    FULFIL(false),
    PERMIT(false);

    public static final String ALWAYS_ON = "Header and Book / order button are always on.";

    private final boolean required;

    SectionKind(boolean required) {
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    /** Checks kind-specific settings; returns the message for the first broken limit. */
    public Optional<String> checkSettings(Map<String, Object> settings) {
        return switch (this) {
            case GALLERY -> tooMany(settings, "media_ids", 12, "Up to 12 photos.");
            case FAQ -> tooMany(settings, "pairs", 8, "Up to 8 questions.");
            case FEATURED -> tooMany(settings, "offer_ids", 6, "Up to 6 featured products.");
            default -> Optional.empty();
        };
    }

    private static Optional<String> tooMany(Map<String, Object> settings, String key, int max, String message) {
        return settings.get(key) instanceof Collection<?> c && c.size() > max ? Optional.of(message) : Optional.empty();
    }
}
