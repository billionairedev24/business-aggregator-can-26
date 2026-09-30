package ca.northline.availability.application;

import ca.northline.availability.application.CalendarSyncEvents.CalendarChanged;
import ca.northline.availability.application.CalendarSyncEvents.ChannelRenewalRequested;
import ca.northline.availability.application.CalendarSyncRepository.Channel;
import ca.northline.availability.application.CalendarUseCases.ReceiveCalendarNotifications;
import ca.northline.availability.domain.CalendarProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies change notifications and queues the read (through the outbox, so the webhook answers at once):
 *
 * <ul>
 *   <li><b>Google</b>: the channel must be ours, the {@code X-Goog-Channel-Token} must match its secret (compared as
 *       SHA-256, constant time) and the resource id must be the one Google gave the channel. {@code sync} messages are
 *       acknowledged; others are deduplicated on channel + {@code X-Goog-Message-Number}.
 *   <li><b>Microsoft Graph</b>: each entry's subscription must be ours and its {@code clientState} match. Changes are
 *       deduplicated on a hash of subscription, change type, resource and etag; lifecycle events renew
 *       ({@code reauthorizationRequired}), recreate ({@code subscriptionRemoved}) or read ({@code missed}).
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class CalendarNotificationService implements ReceiveCalendarNotifications {

    static final Duration VALIDATION_WINDOW = Duration.ofMinutes(2);

    private final CalendarSyncRepository sync;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Outcome google(GoogleNotification n) {
        if (n.channelId() == null || n.token() == null) {
            throw new InvalidNotification("missing channel headers");
        }
        var channel = sync.channel(n.channelId())
                .filter(c -> c.provider() == CalendarProvider.GOOGLE)
                .orElseThrow(() -> new InvalidNotification("unknown channel"));
        if (!CalendarSecrets.matches(n.token(), channel.secretHash())) {
            throw new InvalidNotification("wrong channel token");
        }
        if (channel.externalId() != null && !channel.externalId().equals(n.resourceId())) {
            throw new InvalidNotification("wrong resource");
        }
        if ("sync".equals(n.resourceState())) {
            return Outcome.IGNORED;
        }
        var key = channel.id() + ":" + Objects.requireNonNullElse(n.messageNumber(), "?");
        if (!sync.recordNotification(CalendarProvider.GOOGLE, key, channel.id(), clock.instant())) {
            return Outcome.DUPLICATE;
        }
        events.publishEvent(new CalendarChanged(channel.linkId(), channel.calendarId()));
        return Outcome.ACCEPTED;
    }

    @Override
    @Transactional
    public int microsoft(List<GraphNotification> batch) {
        var verified = 0;
        var accepted = 0;
        for (var n : batch) {
            var channel = verified(n);
            if (channel == null) {
                continue;
            }
            verified++;
            var key = CalendarSecrets.sha256(String.join(
                    "|",
                    channel.id(),
                    Objects.requireNonNullElse(n.lifecycleEvent(), ""),
                    Objects.requireNonNullElse(n.changeType(), ""),
                    Objects.requireNonNullElse(n.resourceId(), ""),
                    Objects.requireNonNullElse(n.etag(), "")));
            if (!sync.recordNotification(CalendarProvider.OUTLOOK, key, channel.id(), clock.instant())) {
                continue;
            }
            accepted++;
            switch (Objects.requireNonNullElse(n.lifecycleEvent(), "")) {
                case "reauthorizationRequired" -> events.publishEvent(new ChannelRenewalRequested(channel.id(), false));
                case "subscriptionRemoved" -> events.publishEvent(new ChannelRenewalRequested(channel.id(), true));
                default -> events.publishEvent(new CalendarChanged(channel.linkId(), channel.calendarId()));
            }
        }
        if (verified == 0 && !batch.isEmpty()) {
            throw new InvalidNotification("no entry verified");
        }
        return accepted;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean acceptsValidation() {
        return sync.pendingChannel(CalendarProvider.OUTLOOK, clock.instant().minus(VALIDATION_WINDOW));
    }

    private @Nullable Channel verified(GraphNotification n) {
        if (n.subscriptionId() == null || n.clientState() == null) {
            return null;
        }
        var channel = sync.channelByExternalId(CalendarProvider.OUTLOOK, n.subscriptionId())
                .orElse(null);
        if (channel == null || !CalendarSecrets.matches(n.clientState(), channel.secretHash())) {
            log.warn("Graph notification for subscription {} refused", n.subscriptionId());
            return null;
        }
        return channel;
    }
}
