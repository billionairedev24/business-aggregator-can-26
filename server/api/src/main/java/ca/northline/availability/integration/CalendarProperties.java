package ca.northline.availability.integration;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.calendar.*} (S-32, docs/runbooks/calendar-sync.md).
 *
 * @param provider {@code local} (fake Google and Outlook; refused under staging/prod) | {@code oauth} (the real
 *     providers; each is offered once its client id and secret are set) — {@code CALENDAR_PROVIDER}
 * @param studioUrl the Studio origin ({@code STUDIO_ORIGIN}): OAuth redirect URIs and links in booking events
 * @param notificationUrl the api's public origin ({@code API_PUBLIC_URL}): change notifications (HTTPS only)
 */
@ConfigurationProperties("northline.calendar")
record CalendarProperties(
        @DefaultValue("local") String provider,
        @DefaultValue("http://localhost:3100") URI studioUrl,
        @DefaultValue("http://localhost:8080") URI notificationUrl,
        @DefaultValue("PT5M") Duration syncInterval,
        @DefaultValue("P180D") Duration readAhead,
        @DefaultValue("P180D") Duration writeAhead,
        @DefaultValue("P6D") Duration channelTtl,
        @DefaultValue Google google,
        @DefaultValue Microsoft microsoft) {

    String effectiveProvider() {
        return provider.isBlank() ? "local" : provider.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Google OAuth client (Web application) — {@code GOOGLE_CALENDAR_CLIENT_ID} / {@code _SECRET}. The URLs are the
     * documented endpoints; tests point them at WireMock.
     */
    record Google(
            @Nullable String clientId,
            @Nullable String clientSecret,

            @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth")
            String authUrl,

            @DefaultValue("https://oauth2.googleapis.com") String tokenUrl,

            @DefaultValue("https://www.googleapis.com/calendar/v3")
            String apiUrl) {

        boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }

        String id() {
            return clientId == null ? "" : clientId.strip();
        }

        String secret() {
            return clientSecret == null ? "" : clientSecret.strip();
        }
    }

    /**
     * Microsoft Entra app registration — {@code MICROSOFT_CALENDAR_CLIENT_ID} / {@code _SECRET}; {@code tenant}
     * ({@code MICROSOFT_CALENDAR_TENANT}) {@code common} accepts work, school and personal accounts.
     */
    record Microsoft(
            @Nullable String clientId,
            @Nullable String clientSecret,
            @DefaultValue("common") String tenant,

            @DefaultValue("https://login.microsoftonline.com")
            String loginUrl,

            @DefaultValue("https://graph.microsoft.com/v1.0")
            String graphUrl) {

        boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }

        String id() {
            return clientId == null ? "" : clientId.strip();
        }

        String secret() {
            return clientSecret == null ? "" : clientSecret.strip();
        }
    }
}
