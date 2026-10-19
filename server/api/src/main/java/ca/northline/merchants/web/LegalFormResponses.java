package ca.northline.merchants.web;

import ca.northline.merchants.application.LegalForm;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.region.api.LegalForms.Condition;
import ca.northline.region.api.LegalForms.Text;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/merchants/{merchantId}/onboarding/legal-forms}: the Business step's legal-details form of every
 * structure in the business's province — the schema's fields with the province's labels, formats, help and registry
 * (region model). The Studio renders it; saving the step is validated against the same definition.
 */
final class LegalFormResponses {
    private LegalFormResponses() {}

    /** @param province the business's province (null before the Account step chose one) */
    record LegalFormsResponse(@Nullable String province, List<LegalFormResponse> items) {}

    /**
     * @param registry / {@code law}: null where the region model has nothing for the structure in the province
     * @param tradeNameRegistration a sole proprietor trading under another name registers it in the province
     * @param verified the province's facts were confirmed on official pages; {@code unverified} lists what was not
     *     (registry · law · identifier · format · attorney)
     */
    record LegalFormResponse(
            BusinessStructure structure,
            @Nullable Text registry,
            @Nullable String registryUrl,
            @Nullable Text law,
            boolean tradeNameRegistration,
            boolean verified,
            List<String> unverified,
            List<LegalFieldResponse> fields) {}

    /**
     * @param kind text · address · date · document · yes_no · choice · stripe_confirmation · group
     * @param requiredWhen required only when another field's value is ({@code in}) or is not ({@code notIn}) one of
     *     the values; with neither, when that field has a value
     * @param label / {@code help} / {@code example}: the province's copy; null = the client's generic copy for the key
     * @param pattern the format of the compacted value (a regular expression); {@code patternMessage} its message
     * @param compact spaces are typing aids: strip them and upper-case before checking
     */
    record LegalFieldResponse(
            String key,
            String kind,
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
            List<LegalFieldResponse> parts) {}

    static LegalFormsResponse of(@Nullable String province, List<LegalForm> forms) {
        return new LegalFormsResponse(
                province, forms.stream().map(LegalFormResponses::form).toList());
    }

    private static LegalFormResponse form(LegalForm f) {
        return new LegalFormResponse(
                f.structure(),
                f.registry(),
                f.registryUrl(),
                f.law(),
                f.tradeNameRegistration(),
                f.verified(),
                f.unverified(),
                f.fields().stream().map(LegalFormResponses::field).toList());
    }

    private static LegalFieldResponse field(LegalForm.Field f) {
        return new LegalFieldResponse(
                f.key(),
                f.kind().name().toLowerCase(Locale.ROOT),
                f.required(),
                f.requiredWhen(),
                f.label(),
                f.help(),
                f.example(),
                f.pattern(),
                f.patternMessage(),
                f.minLength(),
                f.maxLength(),
                f.compact(),
                f.options(),
                f.parts().stream().map(LegalFormResponses::field).toList());
    }
}
