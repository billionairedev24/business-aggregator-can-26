package ca.northline.worker.notifications;

import ca.northline.email.EmailContent;
import java.net.URI;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What one domain event means for one audience — a business's team (S-27), a customer or a courier (S-102): who, which
 * Settings › Notifications row governs it (null = not governed by preferences: a security notice, or a courier's run,
 * sent whatever the matrix and the quiet hours say), which channels the worker sends, the words, and where a push
 * leads.
 *
 * @param merchantId the business the notice is about; null for an order from several shops or a courier's run
 * @param payload the event itself (ids and amounts) — kept with deferred notifications and re-rendered at send time
 */
public record Notice(
        String eventId,
        String type,
        int version,
        @Nullable String merchantId,
        Audience audience,
        @Nullable String row,
        Set<Channel> channels,
        JsonNode payload,
        Texts texts,
        Push push) {

    public Notice {
        channels = Set.copyOf(channels);
    }

    /** A team notice (S-27): the business's members in {@code roles}. */
    public Notice(
            String eventId,
            String type,
            int version,
            String merchantId,
            @Nullable String row,
            Set<String> roles,
            Set<Channel> channels,
            JsonNode payload,
            Texts texts) {
        this(
                eventId,
                type,
                version,
                merchantId,
                new Audience.Team(merchantId, roles),
                row,
                channels,
                payload,
                texts,
                Push.team(type));
    }

    /** Sent whatever the matrix and quiet hours say (security notices, a courier's run). */
    public boolean security() {
        return row == null;
    }

    /** Whether the person's matrix lets this notice through on {@code channel} (security notices always pass). */
    public boolean wantedBy(Recipient person, Channel channel) {
        var governing = row;
        return governing == null || person.preferences().wants(governing, channel);
    }

    /**
     * The row an email's unsubscribe link turns off: a Studio row for the team, {@code customer.<row>} for a customer
     * (the api's unsubscribe endpoint edits the matching matrix).
     */
    public @Nullable String unsubscribeRow() {
        return row == null ? null : audience instanceof Audience.Customer ? "customer." + row : row;
    }

    /** Who a notice is for. */
    public sealed interface Audience {

        /** The app whose installations get the push. */
        PushApp app();

        /** The members of a business in some roles (Studio has no app: their pushes reach no device yet). */
        record Team(String merchantId, Set<String> roles) implements Audience {
            public Team {
                roles = Set.copyOf(roles);
            }

            @Override
            public PushApp app() {
                return PushApp.STUDIO;
            }
        }

        /** One customer (an order's, a booking's, a quote request's or a refund case's). */
        record Customer(String userId) implements Audience {
            @Override
            public PushApp app() {
                return PushApp.CONSUMER;
            }
        }

        /** One courier ({@code identity.users} id, not the courier id). */
        record Courier(String userId) implements Audience {
            @Override
            public PushApp app() {
                return PushApp.COURIER;
            }
        }
    }

    /**
     * Where a push leads and what it carries besides its words: ids only, never personal data (docs/runbooks/push.md).
     *
     * @param path the deep link's path on the consumer host ({@code /app/orders/<id>}), null = none
     * @param data ids for the app ({@code orderId}, {@code bookingId} …), plus {@code type}
     * @param collapseKey a newer push with the same key replaces the older one on the phone
     */
    public record Push(@Nullable String path, Map<String, String> data, String collapseKey) {
        public Push {
            data = Map.copyOf(data);
        }

        static Push team(String type) {
            return new Push(null, Map.of("type", type), type);
        }
    }

    /** The words of this notice for one reader. */
    public interface Texts {
        /** One short message for SMS and the push body (ids and amounts only, no personal data); dates in {@code zone}. */
        String text(String business, Locale locale, ZoneId zone);

        /** The push title. */
        String title(String business, Locale locale, ZoneId zone);

        /** The SMS: {@link #text} (which names Northline itself for the team's notices). */
        default String sms(String business, Locale locale, ZoneId zone) {
            return text(business, locale, zone);
        }

        /**
         * The email, when the worker owns this notice's email ({@code payout.failed}, {@code webhook disabled});
         * null otherwise.
         *
         * @param link the Studio page of {@link #studioPage()} for this business
         */
        default @Nullable EmailContent email(String business, URI link) {
            return null;
        }

        /** The email for one reader; customers' are written in their language (S-102). */
        default @Nullable EmailContent email(String business, URI link, Locale locale, ZoneId zone) {
            return email(business, link);
        }

        /** The Studio page the email links to, relative to {@code /b/<merchantId>/}. */
        default String studioPage() {
            return "payouts";
        }
    }
}
