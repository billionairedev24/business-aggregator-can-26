package ca.northline.worker.push;

import ca.northline.worker.notifications.PushDeliveryFailed;
import ca.northline.worker.notifications.PushSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code northline.push.provider=native}: a push goes to each of the person's installations of the app that allow
 * notifications — APNs for iOS, FCM for Android — in that installation's words.
 *
 * <ul>
 *   <li>A dead token (APNs {@code Unregistered}/{@code BadDeviceToken}, FCM {@code UNREGISTERED}) deletes the device.
 *   <li>Throttling or an outage pauses that provider ({@link Backoff}); while paused it isn't called.
 *   <li>Delivered to at least one installation = sent. Otherwise, when a provider was throttled or down, the push
 *       fails with {@link PushDeliveryFailed} and is retried later (claim released); when every token was dead or
 *       refused, there is no device.
 * </ul>
 */
@Slf4j
public final class DevicePushSender implements PushSender {

    public static final String SENT = "northline.push.sent";

    private final PushDeviceStore devices;
    private final Map<String, PushProvider> providers;
    private final Map<String, Backoff> pauses = new HashMap<>();
    private final Duration staleAfter;
    private final Clock clock;
    private final MeterRegistry meters;

    public DevicePushSender(
            PushDeviceStore devices,
            List<PushProvider> providers,
            PushProperties props,
            Clock clock,
            MeterRegistry meters) {
        this.devices = devices;
        this.providers = new HashMap<>();
        for (var provider : providers) {
            this.providers.put(provider.platform(), provider);
            pauses.put(provider.platform(), new Backoff(props.backoff(), props.maxBackoff()));
        }
        this.staleAfter = props.staleAfter();
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public Result send(PushMessage message) {
        var targets = devices.reachable(
                message.userId(), message.app().code(), clock.instant().minus(staleAfter));
        var delivered = 0;
        PushDeliveryFailed retry = null;
        for (var device : targets) {
            var provider = providers.get(device.platform());
            var pause = pauses.get(device.platform());
            if (provider == null || pause == null) {
                continue;
            }
            var now = clock.instant();
            var left = pause.remaining(now);
            if (left != null) {
                count(device, "paused");
                retry = new PushDeliveryFailed(device.platform() + " push paused for " + left, left);
                continue;
            }
            var outcome = provider.send(device, message.in(device.language()), message);
            count(device, outcome.getClass().getSimpleName().toLowerCase(Locale.ROOT));
            switch (outcome) {
                case PushProvider.Outcome.Delivered _ -> {
                    delivered++;
                    pause.succeeded();
                }
                case PushProvider.Outcome.InvalidToken invalid -> {
                    devices.delete(device.id());
                    log.info(
                            "Push device {} of user {} removed: {} said {} ({})",
                            device.id(),
                            device.userId(),
                            device.platform(),
                            invalid.reason(),
                            device.hint());
                }
                case PushProvider.Outcome.Throttled throttled -> {
                    var wait = pause.failed(now, throttled.retryAfter());
                    log.warn("{} push throttled: paused for {}", device.platform(), wait);
                    retry = new PushDeliveryFailed(device.platform() + " push throttled", wait);
                }
                case PushProvider.Outcome.Unavailable down -> {
                    var wait = pause.failed(now, down.retryAfter());
                    log.warn("{} push unavailable ({}): paused for {}", device.platform(), down.reason(), wait);
                    retry = new PushDeliveryFailed(device.platform() + " push unavailable: " + down.reason(), wait);
                }
                case PushProvider.Outcome.Rejected rejected ->
                    log.error(
                            "{} refused a {} push for good: {} — see docs/runbooks/push.md",
                            device.platform(),
                            message.data().getOrDefault("type", "?"),
                            rejected.reason());
            }
        }
        if (delivered > 0) {
            return Result.DELIVERED;
        }
        if (retry != null) {
            throw retry;
        }
        return Result.NO_DEVICE;
    }

    private void count(PushDevice device, String outcome) {
        meters.counter(SENT, "platform", device.platform(), "app", device.app(), "outcome", outcome)
                .increment();
    }
}
