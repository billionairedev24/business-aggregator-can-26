package ca.northline.messaging.application;

import ca.northline.messaging.domain.CustomerNotificationPrefs;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The consumer's notification settings (S-59): inbound port and the outbound store. */
public final class CustomerNotifications {
    private CustomerNotifications() {}

    /** Every field optional: only what is sent changes. */
    public record Change(
            @Nullable Map<String, Map<String, Boolean>> matrix,
            @Nullable Boolean quietOn,
            @Nullable LocalTime quietFrom,
            @Nullable LocalTime quietTo,
            @Nullable String language,
            @Nullable String marketing) {}

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
