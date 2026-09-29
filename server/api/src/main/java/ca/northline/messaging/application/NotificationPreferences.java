package ca.northline.messaging.application;

import ca.northline.messaging.domain.NotificationMatrix;
import java.util.Map;
import java.util.Optional;

/** Settings › Notifications (the signed-in member's own channel matrix): inbound ports and the outbound store. */
public final class NotificationPreferences {
    private NotificationPreferences() {}

    public interface ViewNotificationMatrix {
        NotificationMatrix view(String userId);
    }

    public interface UpdateNotificationMatrix {
        NotificationMatrix update(String userId, Map<String, Map<String, Boolean>> changes);
    }

    /** Outbound port: {@code messaging.notification_prefs}. */
    public interface NotificationPrefsStore {
        Optional<NotificationMatrix> find(String userId);

        void save(String userId, NotificationMatrix matrix);
    }
}
