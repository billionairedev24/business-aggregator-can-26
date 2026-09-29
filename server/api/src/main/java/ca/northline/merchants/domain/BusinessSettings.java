package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;
import ca.northline.shared.RuleViolation;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Settings › Business: the fields an owner edits after onboarding. {@code serviceArea} lives in onboarding's
 * {@code profile} jsonb, {@code languages} in {@code merchants.languages} (ISO 639 codes).
 */
public record BusinessSettings(
        String merchantId,
        MerchantType type,
        @Nullable BusinessStructure structure,
        DisplayName displayName,
        String legalName,
        @Nullable GstNumber gstNumber,
        @Nullable String serviceArea,
        CancellationPolicy cancellationPolicy,
        @Nullable Long autoAcceptQuoteCents,
        List<String> languages,
        @Nullable String storeSlug) {

    public static final int SERVICE_AREA_MAX = 200;
    public static final String LEGAL_NAME_REQUIRED = "Enter the registered legal name.";
    public static final String SERVICE_AREA_TOO_LONG = "At most 200 characters.";
    public static final String POLICY_REQUIRED = "Choose one of the options.";
    public static final String AMOUNT_RANGE = "Enter an amount of $0 or more.";
    public static final String LANGUAGES_REQUIRED = "Pick at least one language.";
    public static final String LANGUAGE_FORMAT = "Pick languages from the list.";
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}$");

    public BusinessSettings {
        legalName = legalName.strip();
        serviceArea = serviceArea == null || serviceArea.isBlank() ? null : serviceArea.strip();
        languages = languages.stream()
                .map(l -> l.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /** The rules an owner's edit must pass (stored rows from before these rules are shown as they are). */
    public BusinessSettings validated() {
        if (legalName.isEmpty()) {
            throw RuleViolation.of("legalName", "required", LEGAL_NAME_REQUIRED);
        }
        if (gstNumber == null && gstRequired(structure)) {
            throw RuleViolation.of(GstNumber.FIELD, "required", GstNumber.REQUIRED);
        }
        if (serviceArea != null && serviceArea.length() > SERVICE_AREA_MAX) {
            throw RuleViolation.of("serviceArea", "length", SERVICE_AREA_TOO_LONG);
        }
        if (autoAcceptQuoteCents != null && autoAcceptQuoteCents < 0) {
            throw RuleViolation.of("autoAcceptQuoteCents", "range", AMOUNT_RANGE);
        }
        if (languages.isEmpty()) {
            throw RuleViolation.of("languages", "required", LANGUAGES_REQUIRED);
        }
        if (!languages.stream().allMatch(l -> LANGUAGE.matcher(l).matches())) {
            throw RuleViolation.of("languages", "format", LANGUAGE_FORMAT);
        }
        return this;
    }

    /** validation-rules.md › gst_number: optional for sole/partnership, required otherwise. */
    public static boolean gstRequired(@Nullable BusinessStructure structure) {
        return structure != null && structure != BusinessStructure.SOLE && structure != BusinessStructure.PARTNERSHIP;
    }

    /** "Cancellation policy": Flexible · 12 h · 24 h ({@code merchants.cancellation_policy}). */
    public enum CancellationPolicy implements CodedEnum {
        FLEXIBLE("flexible"),
        H12("12h"),
        H24("24h");

        private final String code;

        CancellationPolicy(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }

        public static CancellationPolicy parse(@Nullable String code) {
            if (code == null) {
                throw RuleViolation.of("cancellationPolicy", "required", POLICY_REQUIRED);
            }
            for (var policy : values()) {
                if (policy.code.equals(code)) {
                    return policy;
                }
            }
            throw RuleViolation.of("cancellationPolicy", "allowed", POLICY_REQUIRED);
        }
    }
}
