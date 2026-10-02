package ca.northline.messaging.application;

import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentSource;
import ca.northline.messaging.domain.CustomerNotificationPrefs;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The consumer's notification settings (S-59): inbound port and the outbound store. */
public final class CustomerNotifications {
    private CustomerNotifications() {}

    /**
     * Every field optional: only what is sent changes. The {@code offers} row's cells and {@code marketing} are the
     * person's CASL consents (S-108): a change records a grant or a withdrawal, with {@code consent}'s circumstances.
     */
    public record Change(
            @Nullable Map<String, Map<String, Boolean>> matrix,
            @Nullable Boolean quietOn,
            @Nullable LocalTime quietFrom,
            @Nullable LocalTime quietTo,
            @Nullable String language,
            @Nullable String marketing,
            ConsentContext consent) {

        public Change(
                @Nullable Map<String, Map<String, Boolean>> matrix,
                @Nullable Boolean quietOn,
                @Nullable LocalTime quietFrom,
                @Nullable LocalTime quietTo,
                @Nullable String language,
                @Nullable String marketing) {
            this(matrix, quietOn, quietFrom, quietTo, language, marketing, ConsentContext.SETTINGS);
        }
    }

    /**
     * Where a consent change in the settings happened and how it was shown (S-108).
     *
     * @param language {@code en | fr}: the language the wording was shown in
     * @param wordingVersions the wording versions shown, by channel ({@code email}, {@code sms}, {@code push}); a
     *     missing one = the current wording
     */
    public record ConsentContext(
            ConsentSource source, String language, ConsentEvidence evidence, Map<String, String> wordingVersions) {

        public static final ConsentContext SETTINGS =
                new ConsentContext(ConsentSource.WEB_SETTINGS, "en", ConsentEvidence.NONE, Map.of());

        public ConsentContext {
            wordingVersions = Map.copyOf(wordingVersions);
        }
    }

    public interface ManageCustomerNotifications {
        CustomerNotificationPrefs view(String userId);

        CustomerNotificationPrefs update(String userId, Change change);
    }

    /** Outbound port: the customer columns of {@code messaging.notification_prefs} (V162) and the shared quiet hours. */
    public interface CustomerPrefsStore {
        Optional<CustomerNotificationPrefs> find(String userId);

        void save(String userId, CustomerNotificationPrefs prefs);
    }
}
