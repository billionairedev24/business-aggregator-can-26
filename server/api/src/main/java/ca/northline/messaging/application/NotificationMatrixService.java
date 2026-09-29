package ca.northline.messaging.application;

import ca.northline.messaging.application.NotificationPreferences.NotificationPrefsStore;
import ca.northline.messaging.application.NotificationPreferences.UpdateNotificationMatrix;
import ca.northline.messaging.application.NotificationPreferences.ViewNotificationMatrix;
import ca.northline.messaging.domain.NotificationMatrix;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and edits a member's notification matrix; untouched rows keep the design's defaults. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class NotificationMatrixService implements ViewNotificationMatrix, UpdateNotificationMatrix {

    private final NotificationPrefsStore store;

    @Override
    public NotificationMatrix view(String userId) {
        return store.find(userId).orElseGet(NotificationMatrix::defaultsOnly);
    }

    @Override
    @Transactional
    public NotificationMatrix update(String userId, Map<String, Map<String, Boolean>> changes) {
        var next = NotificationMatrix.edit(changes, view(userId));
        store.save(userId, next);
        return next;
    }
}
