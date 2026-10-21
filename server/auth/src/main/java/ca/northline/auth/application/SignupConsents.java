package ca.northline.auth.application;

import ca.northline.auth.application.SignInLog.Client;
import ca.northline.auth.domain.App;
import ca.northline.auth.domain.AuthMessages;
import ca.northline.auth.domain.SignupConsent;
import ca.northline.email.ConsentProof;
import ca.northline.email.ConsentWordings;
import ca.northline.email.MessageClasses.ConsentCategory;
import ca.northline.platform.EmailProperties;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * S-157, CASL consent on the sign-up forms: the unticked checkboxes of the consumer site's and the Studio's Create
 * account forms and of the app's sign-up. The forms show the wording from {@link #wordings} (the shared catalogue's
 * current one: the account wording for customers, email and text; the Studio's for businesses, email only); a ticked
 * box comes back as its category and wording version, is checked against the catalogue, kept with the pending
 * account, and written to {@code messaging.consent_records} in the transaction that creates the account — an abandoned
 * sign-up leaves no consent behind. Nothing ticked = nothing recorded (never a withdrawal: there was no consent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupConsents {

    private final ConsentRecords records;
    private final EmailProperties email;

    /** A ticked box as the form sends it. */
    public record Requested(String category, String wordingVersion) {}

    /** What a form shows: per category the version and words, and who asks (CASL regulations s. 4). */
    public record Wordings(String requester, List<Item> items) {
        public record Item(String category, String channel, String wordingVersion, String wording) {}
    }

    /** The wordings the app's form shows, in {@code language} ({@code en | fr}); none for the console. */
    public Wordings wordings(App app, String language) {
        var items = new ArrayList<Wordings.Item>();
        surface(app).ifPresent(surface -> {
            for (var category : categories(app)) {
                ConsentWordings.current(category, surface)
                        .ifPresent(w -> items.add(new Wordings.Item(
                                category.code(), category.channel(), w.version(), w.text(language, legalName()))));
            }
        });
        return new Wordings(requester(), items);
    }

    /**
     * Checks the ticked boxes against the app's current wordings and keeps the evidence (422 on a stale wording, a
     * category the app doesn't offer, or a text consent without a mobile number).
     */
    public List<SignupConsent> accept(
            List<Requested> requested, App app, String language, Client client, @Nullable String phone, Instant now) {
        if (requested.isEmpty()) {
            return List.of();
        }
        var surface =
                surface(app).orElseThrow(() -> InvalidInput.of("marketing", "allowed", AuthMessages.CONSENT_CHOOSE));
        var offered = categories(app);
        var accepted = new LinkedHashMap<String, SignupConsent>();
        for (var r : requested) {
            var category = ConsentCategory.ofCode(r.category())
                    .filter(offered::contains)
                    .orElseThrow(() -> InvalidInput.of("marketing", "allowed", AuthMessages.CONSENT_CHOOSE));
            var current = ConsentWordings.current(category, surface).orElseThrow();
            if (!current.version().equals(r.wordingVersion())) {
                throw InvalidInput.of("marketing", "stale", AuthMessages.CONSENT_WORDING_CHANGED);
            }
            if (category == ConsentCategory.MARKETING_SMS && (phone == null || phone.isBlank())) {
                throw InvalidInput.of("phone", "required", AuthMessages.CONSENT_SMS_NEEDS_PHONE);
            }
            accepted.put(
                    category.code(),
                    new SignupConsent(
                            category.code(),
                            current.version(),
                            "fr".equals(language) ? "fr" : "en",
                            now,
                            ConsentProof.ipPrefix(client.ip()),
                            ConsentProof.userAgentHash(client.userAgent())));
        }
        return List.copyOf(accepted.values());
    }

    /**
     * Writes the consents of a new account ({@code source}: {@code web_signup} | {@code app_signup}), each with the
     * hash of the address it covers. In the caller's transaction.
     */
    public void record(
            String userId,
            List<SignupConsent> consents,
            String source,
            @Nullable String email,
            @Nullable String phone) {
        for (var c : consents) {
            var address = ConsentCategory.MARKETING_SMS.code().equals(c.category()) ? phone : email;
            records.append(new ConsentRecords.Grant(
                    UlidCreator.getMonotonicUlid().toString(),
                    userId,
                    c.category(),
                    c.at(),
                    source,
                    c.wordingVersion(),
                    c.language(),
                    address == null || address.isBlank() ? null : ConsentProof.addressHash(address),
                    c.ipPrefix(),
                    c.userAgentHash()));
            log.info("Consent {} granted at sign-up for {} ({})", c.category(), userId, source);
        }
    }

    private static Optional<ConsentWordings.Surface> surface(App app) {
        return switch (app) {
            case CONSUMER -> Optional.of(ConsentWordings.Surface.ACCOUNT);
            case STUDIO -> Optional.of(ConsentWordings.Surface.STUDIO);
            case CONSOLE -> Optional.empty(); // staff accounts get no marketing
        };
    }

    /** Customers may agree to email and texts at sign-up; businesses to email (the Studio's only wording). */
    private static List<ConsentCategory> categories(App app) {
        return app == App.CONSUMER
                ? List.of(ConsentCategory.MARKETING_EMAIL, ConsentCategory.MARKETING_SMS)
                : List.of(ConsentCategory.MARKETING_EMAIL);
    }

    private String legalName() {
        var name = email.legalName();
        return name == null || name.isBlank() ? "Northline" : name.strip();
    }

    private String requester() {
        return ConsentWordings.requester(legalName(), email.mailingAddress(), email.contact());
    }
}
