package ca.northline.auth.web;

import static ca.northline.auth.domain.AuthMessages.BACKUP_CODE_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.CODE_FORMAT;
import static ca.northline.auth.domain.AuthMessages.CODE_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.EMAIL_FORMAT;
import static ca.northline.auth.domain.AuthMessages.EMAIL_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.FIRST_NAME_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.IDENTIFIER_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.LAST_NAME_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.PHONE_FORMAT;
import static ca.northline.auth.domain.AuthMessages.PHONE_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.SIX_DIGITS;
import static ca.northline.auth.domain.AuthMessages.TERMS_REQUIRED;

import ca.northline.auth.domain.PhoneNumber;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** JSON request bodies of the auth API. Messages are the exact ones from validation-rules.md § Registration. */
final class AuthRequests {

    static final String EMAIL = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";

    private AuthRequests() {}

    /** Create account, step 1. Strings are trimmed before validation ("required, trimmed"). */
    record Register(
            @NotBlank(message = FIRST_NAME_REQUIRED) @Nullable
            String firstName,

            @NotBlank(message = LAST_NAME_REQUIRED) @Nullable
            String lastName,

            @NotBlank(message = PHONE_REQUIRED) @Pattern(regexp = PhoneNumber.PATTERN, message = PHONE_FORMAT) @Nullable
            String phone,

            @NotBlank(message = EMAIL_REQUIRED) @Pattern(regexp = EMAIL, message = EMAIL_FORMAT) @Nullable
            String email,

            @NotNull(message = TERMS_REQUIRED) @AssertTrue(message = TERMS_REQUIRED)
            Boolean terms) {

        Register {
            firstName = trim(firstName);
            lastName = trim(lastName);
            phone = trim(phone);
            email = trim(email);
        }
    }

    /** A 6-digit code (phone verification, authenticator app). */
    record Code(
            @NotBlank(message = CODE_REQUIRED) @Pattern(regexp = SIX_DIGITS, message = CODE_FORMAT) @Nullable
            String code) {
        Code {
            code = trim(code);
        }
    }

    /** A printed backup code ({@code abcde-fghij}; spaces, dashes and case are ignored). */
    record BackupCode(
            @NotBlank(message = BACKUP_CODE_REQUIRED) @Nullable
            String code) {}

    /** "Resend" ({@code sms}, default) or "Call me instead" ({@code voice}). */
    record Resend(@Nullable String channel) {}

    /** Sign in, step 1: email or mobile. */
    record Identifier(
            @NotBlank(message = IDENTIFIER_REQUIRED) @Nullable
            String identifier) {}

    /** A WebAuthn credential as produced by {@code PublicKeyCredential.toJSON()}. */
    record Passkey(@NotNull JsonNode credential, @Nullable String label) {}

    private static @Nullable String trim(@Nullable String s) {
        return s == null ? null : s.trim();
    }
}
