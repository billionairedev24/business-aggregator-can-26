package ca.northline.worker.notifications;

import ca.northline.email.EmailContent;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What one domain event means for the team: who (roles), which Settings › Notifications row governs it (null = a
 * security notice: sent whatever the matrix and the quiet hours say), which channels the worker sends (the api sends
 * the S-13 emails), and the words.
 *
 * @param payload the event itself (ids and amounts) — kept with deferred notifications and re-rendered at send time
 */
public record Notice(
        String eventId,
        String type,
        int version,
        String merchantId,
        @Nullable String row,
        Set<String> roles,
        Set<Channel> channels,
        JsonNode payload,
        Texts texts) {

    public Notice {
        roles = Set.copyOf(roles);
        channels = Set.copyOf(channels);
    }

    public boolean security() {
        return row == null;
    }

    /** Whether the member's matrix lets this notice through on {@code channel} (security notices always pass). */
    public boolean wantedBy(Recipient member, Channel channel) {
        var governing = row;
        return governing == null || member.preferences().wants(governing, channel);
    }

    /** The words of this notice for one reader. */
    public interface Texts {
        /** One short message for SMS and the push body (ids and amounts only, no personal data). */
        String text(String business, Locale locale);

        /** The push title. */
        String title(String business, Locale locale);

        /**
         * The email, when the worker owns this notice's email ({@code payout.failed}, {@code webhook disabled});
         * null otherwise.
         *
         * @param link the Studio page of {@link #studioPage()} for this business
         */
        default @Nullable EmailContent email(String business, java.net.URI link) {
            return null;
        }

        /** The Studio page the email links to, relative to {@code /b/<merchantId>/}. */
        default String studioPage() {
            return "payouts";
        }
    }
}
