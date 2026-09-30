package ca.northline.availability.integration;

import ca.northline.availability.application.CalendarGateway;
import ca.northline.availability.application.CalendarSettings;
import ca.northline.availability.domain.CalendarProvider;
import java.time.Clock;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The {@link CalendarGateway}s for {@code northline.calendar.provider} ({@code CALENDAR_PROVIDER}): {@code local} = the
 * fakes (default; refused under {@code staging}/{@code prod}); {@code oauth} = Google Calendar API and Microsoft Graph,
 * each offered once its client id and secret are set (otherwise the Studio shows it as unavailable).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CalendarProperties.class)
class CalendarConfig {

    @Bean
    CalendarSettings calendarSettings(CalendarProperties p) {
        return new CalendarSettings(
                p.studioUrl(), p.notificationUrl(), p.syncInterval(), p.readAhead(), p.writeAhead(), p.channelTtl());
    }

    @Bean
    CalendarGateway googleCalendarGateway(
            CalendarProperties p, CalendarSettings settings, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakeCalendarGateway(CalendarProvider.GOOGLE, settings, clock);
        }
        var google = p.google();
        log.info(
                "Calendar sync: Google {}",
                google.configured() ? "configured" : "not configured (GOOGLE_CALENDAR_CLIENT_ID)");
        return new GoogleCalendarGateway(
                google,
                CalendarHttp.client(GoogleCalendarGateway.OAuthApi.class, google.tokenUrl()),
                CalendarHttp.client(GoogleCalendarGateway.Api.class, google.apiUrl()),
                clock);
    }

    @Bean
    CalendarGateway microsoftCalendarGateway(
            CalendarProperties p, CalendarSettings settings, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakeCalendarGateway(CalendarProvider.OUTLOOK, settings, clock);
        }
        var microsoft = p.microsoft();
        log.info(
                "Calendar sync: Microsoft {}",
                microsoft.configured() ? "configured" : "not configured (MICROSOFT_CALENDAR_CLIENT_ID)");
        return new MicrosoftCalendarGateway(
                microsoft,
                CalendarHttp.client(MicrosoftCalendarGateway.LoginApi.class, microsoft.loginUrl()),
                CalendarHttp.client(MicrosoftCalendarGateway.Graph.class, microsoft.graphUrl()),
                clock);
    }

    private static boolean local(CalendarProperties p, Environment env) {
        return switch (p.effectiveProvider()) {
            case "local" -> {
                if (env.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("CALENDAR_PROVIDER=local is not allowed under staging/prod: set"
                            + " CALENDAR_PROVIDER=oauth (docs/runbooks/calendar-sync.md)");
                }
                yield true;
            }
            case "oauth" -> false;
            default ->
                throw new IllegalStateException(
                        "CALENDAR_PROVIDER must be local or oauth, not " + Objects.requireNonNull(p.provider()));
        };
    }
}
