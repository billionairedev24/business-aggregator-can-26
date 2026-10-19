package ca.northline.merchants.application;

import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.region.api.LegalForms.Condition;
import ca.northline.region.api.LegalForms.Text;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The legal-details form of one business structure in one province: docs/spec/legal-details.schema.json's fields with
 * the province's overlays from the region model ({@code region.legal_forms}). The api serves it to the Studio and
 * validates the Business step against it ({@link LegalDetailsSchema}) — one definition for both.
 *
 * @param province the business's province; null = none yet (the schema's generic form)
 * @param registry / {@code law} / {@code registryUrl}: null where the region model has no row for the structure
 * @param tradeNameRegistration a sole proprietor trading under another name must register it in the province
 * @param verified the region row was confirmed on official pages; {@code unverified} lists what was not
 */
public record LegalForm(
        BusinessStructure structure,
        @Nullable String province,
        @Nullable Text registry,
        @Nullable String registryUrl,
        @Nullable Text law,
        boolean tradeNameRegistration,
        boolean verified,
        List<String> unverified,
        List<Field> fields) {

    public LegalForm {
        unverified = List.copyOf(unverified);
        fields = List.copyOf(fields);
    }

    public Optional<Field> field(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst();
    }

    /** What the Studio renders for a field (and what its value is checked as). */
    public enum Kind {
        TEXT,
        ADDRESS,
        DATE,
        DOCUMENT,
        YES_NO,
        CHOICE,
        /** Stripe collects the SIN: the field only confirms it ({@code true}). */
        STRIPE_CONFIRMATION,
        /** An object of {@link Field#parts()} (the attorney for service). */
        GROUP
    }

    /**
     * @param required always required; {@code requiredWhen}: required only when the condition holds
     * @param label / {@code help} / {@code example}: the province's copy; null = the client's generic copy for the key
     * @param pattern the format the (compacted) value must have; {@code patternMessage} the province's message for it
     * @param compact spaces are typing aids: stripped and upper-cased before validation
     * @param options the values offered (a choice); {@code accepted} every value the api accepts (also older ones)
     */
    public record Field(
            String key,
            Kind kind,
            boolean required,
            @Nullable Condition requiredWhen,
            @Nullable Text label,
            @Nullable Text help,
            @Nullable String example,
            @Nullable String pattern,
            @Nullable Text patternMessage,
            @Nullable Integer minLength,
            @Nullable Integer maxLength,
            boolean compact,
            List<String> options,
            List<String> accepted,
            List<Field> parts) {

        public Field {
            options = List.copyOf(options);
            accepted = List.copyOf(accepted);
            parts = List.copyOf(parts);
        }
    }
}
