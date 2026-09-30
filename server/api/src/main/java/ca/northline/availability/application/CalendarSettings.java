package ca.northline.availability.application;

import java.net.URI;
import java.time.Duration;

/**
 * S-32 settings the services need (bound from {@code northline.calendar.*} by the integration configuration).
 *
 * @param studioBase the Studio origin: OAuth redirect URIs ({@code /api/v1/calendar/oauth/<provider>/callback}, through
 *     the studio-bff) and the link written into each booking event
 * @param notificationBase the api's public origin for change notifications ({@code /api/v1/webhooks/calendar/…});
 *     providers only call HTTPS, so with an {@code http://} origin (a laptop) notifications are off and the periodic
 *     read is all there is
 * @param syncInterval how often every chosen calendar is read and bookings are written back (the design's 5 min)
 * @param readAhead how far ahead busy times are kept
 * @param writeAhead how far ahead bookings are written to the member's calendar
 * @param channelTtl how long a notification channel / subscription is asked for (Graph allows under 7 days)
 */
public record CalendarSettings(
        URI studioBase,
        URI notificationBase,
        Duration syncInterval,
        Duration readAhead,
        Duration writeAhead,
        Duration channelTtl) {

    public boolean notificationsEnabled() {
        return "https".equalsIgnoreCase(notificationBase.getScheme());
    }

    public URI redirectUri(String providerCode) {
        return studioBase.resolve("/api/v1/calendar/oauth/" + providerCode + "/callback");
    }

    public URI notificationUri(String path) {
        return notificationBase.resolve("/api/v1/webhooks/calendar/" + path);
    }
}
