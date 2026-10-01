package ca.northline.availability.application;

import ca.northline.availability.domain.CalendarProvider;
import ca.northline.shared.CodedEnum;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** S-32 use cases: finishing the OAuth connection, choosing calendars, change notifications and the sync jobs. */
public final class CalendarUseCases {
    private CalendarUseCases() {}

    /** The OAuth redirect back from Google / Microsoft (through the studio-bff, so the member is signed in). */
    public interface CompleteCalendarConnection {

        /** Where the Studio lands: {@code /b/<merchant>/availability?calendar=<provider>&result=<outcome>}. */
        enum Outcome implements CodedEnum {
            CONNECTED,
            DENIED,
            FAILED,
            SCOPES,
            EXPIRED
        }

        record Callback(
                String userId,
                CalendarProvider provider,
                @Nullable String code,
                @Nullable String state,
                @Nullable String error) {}

        /** @param choose the member was asked for the calendar list: the Studio reopens "Choose calendars" */
        record Completion(
                @Nullable String merchantId,
                CalendarProvider provider,
                CompleteCalendarConnection.Outcome outcome,
                boolean choose) {}

        Completion complete(Callback callback);
    }

    public record SourceOption(String id, String name, boolean primary, boolean selected) {}

    /**
     * @param authorizationUrl set when the provider first has to allow Northline to list calendars (Google,
     *     incremental consent): send the browser there
     */
    public record SourcesView(
            List<SourceOption> items, @Nullable URI authorizationUrl) {
        public SourcesView {
            items = List.copyOf(items);
        }
    }

    /** "Choose calendars": the member's calendars, the chosen ones marked. */
    public interface ListCalendarSources {
        SourcesView list(String merchantId, String userId, CalendarProvider provider);
    }

    /** Which calendars block Northline slots (at least one). */
    public interface ChooseCalendarSources {
        String NONE = "Choose at least one calendar.";
        String UNKNOWN = "This calendar isn't in your account any more.";

        SourcesView choose(String merchantId, String userId, CalendarProvider provider, List<String> calendarIds);
    }

    /** Change notifications (public webhooks, verified here). */
    public interface ReceiveCalendarNotifications {

        /** Google push headers ({@code X-Goog-Channel-ID}, {@code -Token}, {@code X-Goog-Resource-ID}, …). */
        record GoogleNotification(
                @Nullable String channelId,
                @Nullable String token,
                @Nullable String resourceId,
                @Nullable String resourceState,
                @Nullable String messageNumber) {}

        /** One entry of a Graph notification batch ({@code value[]}), change or lifecycle. */
        record GraphNotification(
                @Nullable String subscriptionId,
                @Nullable String clientState,
                @Nullable String changeType,
                @Nullable String resourceId,
                @Nullable String etag,
                @Nullable String lifecycleEvent) {}

        enum Outcome {
            ACCEPTED,
            DUPLICATE,
            IGNORED
        }

        /** Throws {@link InvalidNotification} for an unknown channel, a wrong token or resource. */
        ReceiveCalendarNotifications.Outcome google(GoogleNotification notification);

        /** The number accepted; throws {@link InvalidNotification} when not one entry verifies. */
        int microsoft(List<GraphNotification> batch);

        /** Graph's validation handshake is answered only while one of our subscriptions is being created. */
        boolean acceptsValidation();

        final class InvalidNotification extends RuntimeException {
            public InvalidNotification(String message) {
                super(message);
            }
        }
    }

    /** The periodic work (safety net for missed notifications); each returns how many items changed. */
    public interface CalendarJobs {
        int syncDue();

        int writeBackDue();

        int renewChannels();

        int purge();
    }
}
