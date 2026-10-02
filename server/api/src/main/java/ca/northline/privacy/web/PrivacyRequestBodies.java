package ca.northline.privacy.web;

import ca.northline.privacy.domain.PrivacyRules;
import ca.northline.privacy.domain.PrivacyRules.Correction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request bodies of the privacy endpoints (people's and staff's). */
final class PrivacyRequestBodies {

    private PrivacyRequestBodies() {}

    static final String TYPES = "access|correction|erasure";

    record CorrectionBody(
            @NotBlank(message = PrivacyRules.CORRECTION_FIELD)
            String field,

            @NotBlank(message = PrivacyRules.CORRECTION_VALUE)
            @Size(max = PrivacyRules.VALUE_MAX, message = PrivacyRules.CORRECTION_VALUE)
            String value) {

        Correction correction() {
            return new Correction(field, value);
        }
    }

    static @Nullable List<Correction> corrections(@Nullable List<CorrectionBody> bodies) {
        return bodies == null
                ? null
                : bodies.stream().map(CorrectionBody::correction).toList();
    }

    record OpenBody(
            @NotBlank(message = PrivacyRules.TYPE_REQUIRED)
            @Pattern(regexp = TYPES, message = PrivacyRules.TYPE_REQUIRED)
            String type,

            @Nullable @Valid List<CorrectionBody> corrections,

            @Nullable @Size(max = PrivacyRules.NOTE_MAX, message = PrivacyRules.NOTE_LENGTH)
            String note) {}

    record VerifyBody(
            @Nullable @Pattern(regexp = "\\s*[0-9]{6}\\s*", message = PrivacyRules.CODE_FORMAT)
            String code) {}

    record RecordBody(
            @NotBlank(message = PrivacyRules.CONTACT_REQUIRED) @Size(max = 254, message = PrivacyRules.CONTACT_REQUIRED)
            String contact,

            @NotBlank(message = PrivacyRules.TYPE_REQUIRED)
            @Pattern(regexp = TYPES, message = PrivacyRules.TYPE_REQUIRED)
            String type,

            @Nullable @Valid List<CorrectionBody> corrections,

            @Nullable @Size(max = PrivacyRules.NOTE_MAX, message = PrivacyRules.NOTE_LENGTH)
            String note) {}

    record ExtendBody(
            @NotBlank(message = PrivacyRules.EXTENSION_REQUIRED)
            @Pattern(regexp = "volume|consultation|conversion", message = PrivacyRules.EXTENSION_REQUIRED)
            String reason) {}

    record RejectBody(
            @NotBlank(message = PrivacyRules.DECISION_REQUIRED)
            @Pattern(
                    regexp = "identity_not_verified|not_our_data|legal_exception|duplicate|frivolous",
                    message = PrivacyRules.DECISION_REQUIRED)
            String decision,

            @Nullable @Size(max = 500, message = PrivacyRules.DECISION_NOTE)
            String note) {}

    record CorrectBody(
            @NotEmpty(message = PrivacyRules.CORRECTIONS_REQUIRED) @Valid
            List<CorrectionBody> corrections) {}
}
