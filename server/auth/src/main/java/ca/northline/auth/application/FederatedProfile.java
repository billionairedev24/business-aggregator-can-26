package ca.northline.auth.application;

import org.jspecify.annotations.Nullable;

/**
 * What Google or Apple vouched for after "Continue with Google/Apple" (S-18).
 *
 * @param provider {@code google} | {@code apple}
 * @param subject the provider's stable account id ({@code sub})
 * @param emailVerified the provider says the email is the person's (Google {@code email_verified}; Apple always)
 * @param privateRelay Apple "Hide My Email" ({@code is_private_email}): a {@code …@privaterelay.appleid.com} address
 * @param givenName null when the provider didn't say (Apple only sends the name the first time)
 */
public record FederatedProfile(
        String provider,
        String subject,
        @Nullable String email,
        boolean emailVerified,
        boolean privateRelay,
        @Nullable String givenName,
        @Nullable String familyName) {}
