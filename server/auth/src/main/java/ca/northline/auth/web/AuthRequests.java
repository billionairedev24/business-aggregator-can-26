package ca.northline.auth.web;

import static ca.northline.auth.domain.AuthMessages.APP_UNKNOWN;
import static ca.northline.auth.domain.AuthMessages.BACKUP_CODE_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.BACKUP_CODE_WRONG;
import static ca.northline.auth.domain.AuthMessages.CODE_FORMAT;
import static ca.northline.auth.domain.AuthMessages.CODE_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.CONSENT_CHOOSE;
import static ca.northline.auth.domain.AuthMessages.EMAIL_FORMAT;
import static ca.northline.auth.domain.AuthMessages.EMAIL_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.FIRST_NAME_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.IDENTIFIER_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.LAST_NAME_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.LINK_EXPIRED;
import static ca.northline.auth.domain.AuthMessages.PASSWORD_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.PHONE_FORMAT;
import static ca.northline.auth.domain.AuthMessages.PHONE_REQUIRED;
import static ca.northline.auth.domain.AuthMessages.SIX_DIGITS;
import static ca.northline.auth.domain.AuthMessages.TERMS_LANGUAGE;
import static ca.northline.auth.domain.AuthMessages.TERMS_REQUIRED;

import ca.northline.auth.domain.PhoneNumber;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** JSON request bodies of the auth API. Messages are the exact ones from validation-rules.md § Registration. */
final class AuthRequests {

    static final String EMAIL = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";

    private AuthRequests() {}

    /**
     * S-157: a ticked marketing checkbox on a sign-up form (CASL express consent): the category and the wording version
     * the form showed ({@code GET /api/auth/accounts/consent-wordings}). Unticked boxes are simply not sent.
     */
    record MarketingConsent(
            @NotBlank(message = CONSENT_CHOOSE)
            @Pattern(regexp = "^(marketing_email|marketing_sms)$", message = CONSENT_CHOOSE)
            @Nullable
            String category,

            @NotBlank(message = CONSENT_CHOOSE)
            @Pattern(regexp = "^[a-z0-9_.-]{3,64}$", message = CONSENT_CHOOSE)
            @Nullable
            String wordingVersion) {}

    static List<ca.northline.auth.application.SignupConsents.Requested> requested(
            @Nullable List<MarketingConsent> marketing) {
        return marketing == null
                ? List.of()
                : marketing.stream()
                        .map(m -> new ca.northline.auth.application.SignupConsents.Requested(
                                java.util.Objects.requireNonNull(m.category()),
                                java.util.Objects.requireNonNull(m.wordingVersion())))
                        .toList();
    }

    /**
     * Create account, step 1. Strings are trimmed before validation ("required, trimmed"). S-116: {@code termsLanguage}
     * is the language the Terms and Privacy Policy were shown in ({@code en} | {@code fr}; absent = not said), and
     * {@code termsEnglishRequested} records that the person expressly asked for the English version where the Terms
     * are presented in French first (a French-first place, region configuration) — kept with the acceptance.
     */
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
            Boolean terms,

            @Pattern(regexp = "^(en|fr)$", message = TERMS_LANGUAGE) @Nullable
            String termsLanguage,

            @Nullable Boolean termsEnglishRequested,

            @Size(max = 2, message = CONSENT_CHOOSE) @Nullable
            List<@Valid MarketingConsent> marketing,

            @Pattern(regexp = "^(web_signup|app_signup)$", message = CONSENT_CHOOSE) @Nullable
            String consentSource) {

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
            @NotBlank(message = BACKUP_CODE_REQUIRED) @Size(max = 64, message = BACKUP_CODE_WRONG) @Nullable
            String code) {}

    /** "Resend" ({@code sms}, default) or "Call me instead" ({@code voice}). */
    record Resend(@Nullable String channel) {}

    /** Sign in, step 1: email or mobile. */
    record Identifier(
            @NotBlank(message = IDENTIFIER_REQUIRED) @Size(max = 320, message = IDENTIFIER_REQUIRED) @Nullable
            String identifier) {}

    static final String APPS = "^(studio|consumer|console)$";

    /**
     * S-154, Create account with email + password. {@code app}: where the person is ({@code studio} by default) — the
     * email's links open there, and the Studio and the console then require a second factor. {@code phone} is
     * optional. The password is not trimmed (spaces count); {@code passwordConfirm}, when sent, must match.
     */
    record CreateAccount(
            @NotBlank(message = FIRST_NAME_REQUIRED) @Size(max = 80, message = FIRST_NAME_REQUIRED) @Nullable
            String firstName,

            @NotBlank(message = LAST_NAME_REQUIRED) @Size(max = 80, message = LAST_NAME_REQUIRED) @Nullable
            String lastName,

            @NotBlank(message = EMAIL_REQUIRED)
            @Pattern(regexp = EMAIL, message = EMAIL_FORMAT)
            @Size(max = 320, message = EMAIL_FORMAT)
            @Nullable
            String email,

            @NotEmpty(message = PASSWORD_REQUIRED) @Nullable String password,
            @Nullable String passwordConfirm,

            @Pattern(regexp = PhoneNumber.PATTERN, message = PHONE_FORMAT) @Nullable
            String phone,

            @NotNull(message = TERMS_REQUIRED) @AssertTrue(message = TERMS_REQUIRED)
            Boolean terms,

            @Pattern(regexp = "^(en|fr)$", message = TERMS_LANGUAGE) @Nullable
            String termsLanguage,

            @Nullable Boolean termsEnglishRequested,

            @Pattern(regexp = APPS, message = APP_UNKNOWN) @Nullable
            String app,

            @Size(max = 2, message = CONSENT_CHOOSE) @Nullable
            List<@Valid MarketingConsent> marketing) {

        CreateAccount {
            firstName = trim(firstName);
            lastName = trim(lastName);
            email = trim(email);
            phone = phone == null || phone.isBlank() ? null : phone.trim();
        }
    }

    /** S-154, sign in step 1: email + password. */
    record EmailPassword(
            @NotBlank(message = EMAIL_REQUIRED) @Size(max = 320, message = EMAIL_FORMAT) @Nullable
            String email,

            @NotEmpty(message = PASSWORD_REQUIRED) @Size(max = 1024, message = PASSWORD_REQUIRED) @Nullable
            String password) {
        EmailPassword {
            email = trim(email);
        }
    }

    /** S-154: the link of an account email ({@code ?token=}). */
    record Token(
            @NotBlank(message = LINK_EXPIRED) @Size(max = 128, message = LINK_EXPIRED) @Nullable
            String token) {}

    /** S-154: Forgot password. */
    record Forgot(
            @NotBlank(message = EMAIL_REQUIRED)
            @Pattern(regexp = EMAIL, message = EMAIL_FORMAT)
            @Size(max = 320, message = EMAIL_FORMAT)
            @Nullable
            String email,

            @Pattern(regexp = APPS, message = APP_UNKNOWN) @Nullable
            String app) {
        Forgot {
            email = trim(email);
        }
    }

    /** S-154: the new password, with the email's code (this browser) or its link's token (any browser). */
    record ResetPassword(
            @Pattern(regexp = SIX_DIGITS, message = CODE_FORMAT) @Nullable
            String code,

            @Size(max = 128, message = LINK_EXPIRED) @Nullable
            String token,

            @NotEmpty(message = PASSWORD_REQUIRED) @Nullable String password,
            @Nullable String passwordConfirm) {
        ResetPassword {
            code = trim(code);
            token = trim(token);
        }
    }

    /** S-154, Settings › Security: change (or set a first) password. */
    record ChangePassword(
            @Nullable String currentPassword,
            @NotEmpty(message = PASSWORD_REQUIRED) @Nullable String password,
            @Nullable String passwordConfirm,
            @Nullable String current) {}

    /** A WebAuthn credential as produced by {@code PublicKeyCredential.toJSON()}. */
    record Passkey(@NotNull JsonNode credential, @Nullable String label) {}

    private static @Nullable String trim(@Nullable String s) {
        return s == null ? null : s.trim();
    }
}
