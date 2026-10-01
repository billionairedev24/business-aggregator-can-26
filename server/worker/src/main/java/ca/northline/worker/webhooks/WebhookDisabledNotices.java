package ca.northline.worker.webhooks;

import ca.northline.email.EmailContent;
import ca.northline.worker.notifications.Channel;
import ca.northline.worker.notifications.Notice;
import ca.northline.worker.notifications.Notifier;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the owners when the worker turned one of their endpoints off (S-33), through the S-27 notification channels:
 * an email to every owner (a service notice — sent whatever the Settings › Notifications matrix says), claimed once
 * per owner under the endpoint's {@code disable_notice_id}. An unavailable email provider leaves the endpoint marked
 * "not told" and the next run tries again (for up to {@link #GIVE_UP_AFTER}).
 */
@Slf4j
public final class WebhookDisabledNotices {

    static final String TYPE = "developer.webhook_endpoint_disabled";
    static final Duration GIVE_UP_AFTER = Duration.ofDays(2);

    private final WebhookStore store;
    private final Notifier notifier;
    private final JsonMapper json;
    private final Clock clock;

    public WebhookDisabledNotices(WebhookStore store, Notifier notifier, JsonMapper json, Clock clock) {
        this.store = store;
        this.notifier = notifier;
        this.json = json;
        this.clock = clock;
    }

    /** Emails the owners of endpoints turned off and not told yet; returns how many endpoints were handled. */
    public int send() {
        var done = 0;
        for (var endpoint : store.unnotified(clock.instant().minus(GIVE_UP_AFTER), 20)) {
            try {
                notifier.notify(notice(endpoint));
                store.notified(endpoint.id(), clock.instant());
                done++;
            } catch (RuntimeException e) {
                log.warn("Owners of webhook endpoint {} not emailed yet: {}", endpoint.id(), e.toString());
            }
        }
        return done;
    }

    Notice notice(WebhookStore.DisabledEndpoint endpoint) {
        var payload = json.createObjectNode();
        payload.put("merchantId", endpoint.merchantId());
        payload.put("endpointId", endpoint.id());
        return new Notice(
                endpoint.noticeId(),
                TYPE,
                1,
                endpoint.merchantId(),
                null,
                Set.of("owner"),
                EnumSet.of(Channel.EMAIL),
                payload,
                new Notice.Texts() {
                    @Override
                    public String text(String business, Locale locale, ZoneId zone) {
                        return french(locale)
                                ? "Northline : webhook désactivé pour " + business + " après 3 jours d’échecs."
                                : "Northline: a webhook endpoint for " + business
                                        + " was turned off after 3 days of failures.";
                    }

                    @Override
                    public String title(String business, Locale locale, ZoneId zone) {
                        return french(locale) ? "Webhook désactivé" : "Webhook turned off";
                    }

                    @Override
                    public EmailContent email(String business, URI link) {
                        return new EmailContent.WebhookDisabled(
                                business, endpoint.url(), endpoint.failingSince(), endpoint.lastError(), link);
                    }

                    @Override
                    public String studioPage() {
                        return "settings?tab=api";
                    }
                });
    }

    private static boolean french(Locale locale) {
        return "fr".equals(locale.getLanguage());
    }
}
