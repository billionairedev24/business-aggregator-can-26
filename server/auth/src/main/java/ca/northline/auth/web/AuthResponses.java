package ca.northline.auth.web;

import ca.northline.auth.application.UserAccount;
import ca.northline.auth.application.UserClaimsService;
import ca.northline.auth.domain.Factor;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** JSON response bodies of the auth API. */
final class AuthResponses {

    private AuthResponses() {}

    /** Where the registration is: {@code otp} (code sent) or {@code mfa} (phone verified). */
    record RegistrationStep(String step, String phone, long resendAfterSeconds, String channel) {}

    /** Sign-in started: the factors offered (always all three, whether or not the account exists). */
    record SignInStarted(String identifier, List<String> factors) {}

    record TotpSetup(String secret, String otpauthUri, String qrCode) {}

    /** S-62: a sign-in code is on its way (the same answer whether or not an account matched). */
    record CodeSent(long resendAfterSeconds, String channel) {}

    record BackupCodes(List<String> codes) {}

    /** Same shape as the BFF's {@code GET /bff/session} user. */
    record User(
            String id,
            String firstName,
            String lastName,
            @Nullable String email,
            @Nullable String phone,
            String initials,
            String locale,
            String memberSince) {

        /** @param zone the platform zone: an account's "member since" date belongs to no market */
        static User of(UserAccount a, ZoneId zone) {
            var first = a.givenName();
            var last = a.familyName();
            var initials = ((first.isEmpty() ? "" : first.substring(0, 1))
                            + (last.isEmpty() ? "" : last.substring(0, 1)))
                    .toUpperCase(Locale.ROOT);
            return new User(
                    a.id(),
                    first,
                    last,
                    a.email(),
                    a.phone(),
                    initials.isEmpty() ? "NL" : initials,
                    a.locale() == null ? "en-CA" : a.locale(),
                    a.createdAt().atZone(zone).toLocalDate().toString());
        }
    }

    /**
     * Signed in (auth server session): who, and {@code acr=mfa} when a second factor was used. {@code continueTo}
     * (S-29): the authorization request of a mobile app that sent the browser to the sign-in page, to go back to
     * instead of the Studio's BFF hand-off.
     */
    record Session(
            User user, @Nullable String acr, @Nullable String continueTo) {
        static Session of(UserAccount account, Collection<Factor> factors, ZoneId zone) {
            return new Session(User.of(account, zone), Factor.isMfa(factors) ? UserClaimsService.MFA : null, null);
        }

        Session continuingTo(@Nullable String url) {
            return new Session(user, acr, url);
        }
    }
}
