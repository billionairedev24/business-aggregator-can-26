package ca.northline.messaging.application;

import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentRecord;
import ca.northline.messaging.domain.ConsentSource;
import ca.northline.messaging.domain.ConsentWordings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** CASL consent to commercial messages (S-108): inbound ports, the views and the outbound store. */
public final class Consents {
    private Consents() {}

    /**
     * One category now, with the wording a person reads before turning it on.
     *
     * @param since when the current state began (the newest record); null = never asked
     * @param source where that happened; null = never asked
     * @param wording the current wording for the place, in the reader's language, the legal name filled in
     */
    public record Current(
            ConsentCategory category,
            boolean granted,
            @Nullable Instant since,
            @Nullable ConsentSource source,
            String wordingVersion,
            String wording) {}

    /**
     * A person's consents: the state per category, the full history (newest first) and who asks (CASL regulations s. 4:
     * the requester's legal name, mailing address and contact, shown with the wording).
     */
    public record View(List<Current> categories, List<ConsentRecord> history, String requester) {

        public View {
            categories = List.copyOf(categories);
            history = List.copyOf(history);
        }

        public boolean granted(ConsentCategory category) {
            return categories.stream().anyMatch(c -> c.category() == category && c.granted());
        }
    }

    /**
     * A grant or withdrawal by the person (or for them).
     *
     * @param wordingVersion the wording the person was shown; null = the current one for the place
     * @param language {@code en | fr}, the language the wording or page was in
     */
    public record Change(
            ConsentCategory category,
            boolean granted,
            ConsentSource source,
            @Nullable String wordingVersion,
            String language,
            ConsentEvidence evidence) {}

    /** The person's own consents (Account › Notifications, Studio, sign-up, checkout; unsubscribe links). */
    public interface ManageConsents {

        View view(String userId, ConsentWordings.Surface surface, String language);

        /**
         * Records the change unless the category is already in that state (idempotent: a second unsubscribe, a save
         * without a change, records nothing). A grant needs a {@link ConsentSource#PERSONAL} source and the current
         * wording; the address it covers is hashed from the account's contact at that moment.
         */
        void change(String userId, Change change);

        /** Whether each category is granted now: what senders ask right before a commercial message goes. */
        Map<ConsentCategory, Boolean> granted(String userId);
    }

    /** Staff (console screen {@code privacy}): proof of consent and withdrawals made by phone, mail or email. */
    public interface ConsentDesk {

        /**
         * Every record of the person with this id, or of any account a contact (email or phone) was given for — found
         * by the address hash, so it works after an erasure.
         */
        List<ConsentRecord> lookup(@Nullable String userId, @Nullable String contact);

        /** Withdraws one category for the person, audit-logged; nothing when it isn't granted. */
        void withdraw(String userId, ConsentCategory category, String staffId, String staffRoles);
    }

    /** Outbound port: {@code messaging.consent_records} (V300), append-only. */
    public interface ConsentStore {

        /** Newest first. */
        List<ConsentRecord> history(String userId);

        Optional<ConsentRecord> latest(String userId, ConsentCategory category);

        void append(ConsentRecord record);

        List<ConsentRecord> byAddressHash(String addressHash);

        /** Erasure (S-105): drops the network and browser evidence; the proof (who, what, when, how) stays. */
        void minimise(String userId);

        /** Deletes every record of a (person, category) whose newest record is a withdrawal before {@code before}. */
        int purgeWithdrawnBefore(Instant before);
    }
}
