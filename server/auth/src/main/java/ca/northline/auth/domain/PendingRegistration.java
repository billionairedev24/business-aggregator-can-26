package ca.northline.auth.domain;

import java.io.Serializable;
import java.util.List;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * A registration in progress (form → phone code → second factor). Lives in the auth server's HTTP session until the
 * second factor is confirmed; only then is the {@code identity.users} row written, so an abandoned registration leaves
 * no account behind.
 *
 * @param userId the ULID the account will get (also the WebAuthn user name)
 * @param totpSecret the authenticator secret offered to the user, until it is confirmed
 * @param termsLanguage the language the Terms were shown in ({@code en} | {@code fr}), null when the client didn't say
 * @param termsEnglishRequested the person expressly asked for the English Terms where French comes first (S-116)
 * @param marketingConsents S-157: the sign-up form's ticked marketing checkboxes, recorded with the account
 * @param consentSource S-157: {@code web_signup} | {@code app_signup}
 */
@With
public record PendingRegistration(
        String userId,
        String firstName,
        String lastName,
        PhoneNumber phone,
        String email,
        String termsVersion,
        OtpChallenge otp,
        boolean phoneVerified,
        @Nullable String totpSecret,
        @Nullable String termsLanguage,
        boolean termsEnglishRequested,
        List<SignupConsent> marketingConsents,
        String consentSource)
        implements Serializable {

    public PendingRegistration {
        marketingConsents = List.copyOf(marketingConsents);
    }

    public String fullName() {
        return (firstName + " " + lastName).trim();
    }
}
