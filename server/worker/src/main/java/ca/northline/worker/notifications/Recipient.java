package ca.northline.worker.notifications;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A team member to notify: where (email, E.164 mobile), in which language, and their preferences. Read at the moment
 * of sending, never carried in events.
 *
 * @param role the member's role in the business ({@code owner}, {@code bookkeeper}, …)
 */
public record Recipient(
        String userId,
        String role,
        String displayName,
        @Nullable String email,
        @Nullable String phone,
        Locale locale,
        Preferences preferences) {}
