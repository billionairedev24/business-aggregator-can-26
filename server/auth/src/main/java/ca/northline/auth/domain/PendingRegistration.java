package ca.northline.auth.domain;

import java.io.Serializable;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * A registration in progress (form → phone code → second factor). Lives in the auth server's HTTP session until the
 * second factor is confirmed; only then is the {@code identity.users} row written, so an abandoned registration leaves
 * no account behind.
 *
 * @param userId the ULID the account will get (also the WebAuthn user name)
 * @param totpSecret the authenticator secret offered to the user, until it is confirmed
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
        @Nullable String totpSecret)
        implements Serializable {

    public String fullName() {
        return (firstName + " " + lastName).trim();
    }
}
