package ca.northline.merchants.web;

import ca.northline.merchants.domain.BusinessSettings;
import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.GstNumber;
import ca.northline.merchants.domain.TeamRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request/response bodies of Settings › Business and Settings › Team &amp; roles, and Stripe &amp; compliance. */
final class SettingsDtos {
    private SettingsDtos() {}

    /** Messages are the domain constants (validation-rules.md where it has them). */
    record UpdateBusinessRequest(
            @NotBlank(message = DisplayName.REQUIRED)
            @Size(min = DisplayName.MIN, message = DisplayName.TOO_SHORT)
            @Size(max = DisplayName.MAX, message = DisplayName.TOO_LONG)
            String displayName,

            @NotBlank(message = BusinessSettings.LEGAL_NAME_REQUIRED)
            String legalName,

            @Nullable @Pattern(regexp = "^\\s*$|" + GstNumber.REGEX, message = GstNumber.FORMAT)
            String gstNumber,

            @Nullable @Size(max = BusinessSettings.SERVICE_AREA_MAX, message = BusinessSettings.SERVICE_AREA_TOO_LONG)
            String serviceArea,

            @NotBlank(message = BusinessSettings.POLICY_REQUIRED)
            @Pattern(regexp = "flexible|12h|24h", message = BusinessSettings.POLICY_REQUIRED)
            String cancellationPolicy,

            @Nullable @PositiveOrZero(message = BusinessSettings.AMOUNT_RANGE)
            Long autoAcceptQuoteCents,

            @NotEmpty(message = BusinessSettings.LANGUAGES_REQUIRED)
            List<String> languages) {}

    /**
     * @param gstRequired whether this structure must have a GST number (validation-rules.md)
     * @param storeSlug the public page's slug (API tab › "Embed your store")
     */
    record BusinessResponse(
            String type,
            @Nullable String structure,
            String displayName,
            String legalName,
            @Nullable String gstNumber,
            boolean gstRequired,
            @Nullable String serviceArea,
            String cancellationPolicy,
            @Nullable Long autoAcceptQuoteCents,
            List<String> languages,
            @Nullable String storeSlug) {

        static BusinessResponse of(BusinessSettings s) {
            var gst = s.gstNumber();
            var structure = s.structure();
            return new BusinessResponse(
                    s.type().code(),
                    structure == null ? null : structure.code(),
                    s.displayName().value(),
                    s.legalName(),
                    gst == null ? null : gst.value(),
                    BusinessSettings.gstRequired(structure),
                    s.serviceArea(),
                    s.cancellationPolicy().code(),
                    s.autoAcceptQuoteCents(),
                    s.languages(),
                    s.storeSlug());
        }
    }

    /** Email or mobile (one of them); the role must be one the business offers. */
    record InviteRequest(
            @Nullable String email,
            @Nullable String phone,
            @NotBlank(message = TeamRules.ROLE_REQUIRED) String role) {}

    record ChangeRoleRequest(
            @NotBlank(message = TeamRules.ROLE_REQUIRED) String role) {}

    record AcceptObligationsRequest(@NotBlank String version) {}

    record StripeLinkRequest(
            @NotBlank @Pattern(regexp = "dashboard|update") String kind) {}

    record LinkResponse(String url) {}
}
