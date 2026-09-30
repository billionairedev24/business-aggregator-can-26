package ca.northline.auth.application;

import ca.northline.auth.domain.PendingFederation;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Continue with Google / Apple" (S-18). Google and Apple vouch for who holds an email address, which is not a
 * business second factor, so a federated login never becomes the session by itself:
 *
 * <ul>
 *   <li>a provider account already linked → the Studio's factor step for that account;
 *   <li>a <b>verified</b> email of an existing account → the factor step too, and the provider account is linked once
 *       that second factor succeeds ({@link FederatedLinking});
 *   <li>anything else → "Create account" pre-filled with the name and (verified) email; phone code and second factor
 *       as for every registration, and the provider account is linked to the new account.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FederatedSignInService {

    private final FederatedIdentities identities;
    private final UserAccounts accounts;
    private final SignInService signIn;
    private final FederatedLinking linking;

    /** Where the Studio continues. */
    public sealed interface Next permits ContinueSignIn, CreateAccount {}

    /** The factor step for {@code identifier}; {@code linking}: the provider account is linked after it. */
    public record ContinueSignIn(String identifier, boolean linking) implements Next {}

    /** "Create account" pre-filled; {@code email} only when the provider verified it. */
    public record CreateAccount(
            String firstName, String lastName, @Nullable String email, boolean privateRelay) implements Next {}

    @Transactional(readOnly = true)
    public Next signedIn(FederatedProfile profile) {
        var email = profile.emailVerified() ? profile.email() : null;
        var linked = identities
                .userOf(profile.provider(), profile.subject())
                .flatMap(accounts::findById)
                .filter(UserAccount::active);
        if (linked.isPresent()) {
            return continueWith(profile, linked.get(), true);
        }
        var byEmail = email == null
                ? null
                : accounts.findByEmail(email).filter(UserAccount::active).orElse(null);
        if (byEmail != null) {
            return continueWith(profile, byEmail, false);
        }
        linking.remember(new PendingFederation(
                profile.provider(), profile.subject(), email, profile.privateRelay(), null, false));
        return new CreateAccount(
                Objects.requireNonNullElse(profile.givenName(), ""),
                Objects.requireNonNullElse(profile.familyName(), ""),
                email,
                profile.privateRelay());
    }

    private Next continueWith(FederatedProfile profile, UserAccount account, boolean alreadyLinked) {
        var identifier = account.email() != null ? account.email() : Objects.requireNonNull(account.phone());
        signIn.start(identifier); // S-9 lookup limits apply as for a typed identifier
        linking.remember(new PendingFederation(
                profile.provider(),
                profile.subject(),
                profile.email(),
                profile.privateRelay(),
                account.id(),
                alreadyLinked));
        return new ContinueSignIn(identifier, !alreadyLinked);
    }
}
