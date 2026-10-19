package ca.northline.region.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a province or territory asks of each business structure in onboarding (2026-10-19, DECISIONS "Province legal
 * details"): its registry, the governing act, whether a sole proprietor's trade name must be registered, and overlays
 * on the legal-details fields (label, help, example, format, required-ness) — rows of {@code region.legal_forms}
 * (V372). Structures are the codes of docs/spec/legal-details.schema.json. Code never names a province; it asks here
 * with the business's province.
 */
public interface LegalForms {

    Optional<LegalForm> form(@Nullable String province, String structure);

    /** Every row of the province, in the schema's structure order; empty for an unknown province. */
    List<LegalForm> forms(@Nullable String province);

    /** Copy in both languages. */
    record Text(String en, String fr) {
        public String in(Locale locale) {
            return locale.getLanguage().equals("fr") ? fr : en;
        }
    }

    /**
     * A field is required only when another field's value is (or is not) one of the listed values; with neither list,
     * when that field has a value.
     */
    record Condition(String field, List<String> in, List<String> notIn) {
        public Condition {
            in = List.copyOf(in);
            notIn = List.copyOf(notIn);
        }

        public boolean holds(@Nullable Object value) {
            var text = value == null ? null : String.valueOf(value);
            if (!in.isEmpty()) {
                return text != null && in.contains(text);
            }
            if (!notIn.isEmpty()) {
                return text == null || !notIn.contains(text);
            }
            return text != null && !text.isBlank();
        }
    }

    /**
     * The province's overlay on one schema field; null members keep the schema's (and the client's generic copy).
     *
     * @param pattern a regular expression the compacted value must match; null = any text
     * @param required replaces the schema's required-ness; {@code requiredWhen} makes it conditional
     */
    record FieldRule(
            @Nullable Text label,
            @Nullable Text help,
            @Nullable String example,
            @Nullable String pattern,
            @Nullable Text patternMessage,
            @Nullable Boolean required,
            @Nullable Condition requiredWhen) {}

    /**
     * @param tradeNameRegistration a sole proprietor trading under another name must register it in the province
     * @param fields overlays by schema field key
     * @param verified every fact was confirmed on an official government page; {@code unverified} lists what was not
     */
    record LegalForm(
            String province,
            String structure,
            Text registry,
            @Nullable String registryUrl,
            Text law,
            boolean tradeNameRegistration,
            Map<String, FieldRule> fields,
            String source,
            boolean verified,
            List<String> unverified) {

        public LegalForm {
            fields = Map.copyOf(fields);
            unverified = List.copyOf(unverified);
        }

        public Optional<FieldRule> field(String key) {
            return Optional.ofNullable(fields.get(key));
        }
    }
}
