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

    private static final ZoneId EDMONTON = ZoneId.of("America/Edmonton");

    private AuthResponses() {}

    /** Where the registration is: {@code otp} (code sent) or {@code mfa} (phone verified). */
    record RegistrationStep(String step, String phone, long resendAfterSeconds, String channel) {}

    /** Sign-in started: the factors offered (always all three, whether or not the account exists). */
    record SignInStarted(String identifier, List<String> factors) {}

    record TotpSetup(String secret, String otpauthUri, String qrCode) {}

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

        static User of(UserAccount a) {
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
                    a.createdAt().atZone(EDMONTON).toLocalDate().toString());
        }
    }

    /**
     * Signed in (auth server session): who, and {@code acr=mfa} when a second factor was used. {@code continueTo}
     * (S-29): the authorization request of a mobile app that sent the browser to the sign-in page, to go back to
     * instead of the Studio's BFF hand-off.
     */
    record Session(
            User user, @Nullable String acr, @Nullable String continueTo) {
        static Session of(UserAccount account, Collection<Factor> factors) {
            return new Session(User.of(account), Factor.isMfa(factors) ? UserClaimsService.MFA : null, null);
        }

        Session continuingTo(@Nullable String url) {
            return new Session(user, acr, url);
        }
    }
}
