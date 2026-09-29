package ca.northline.messaging.domain;

import ca.northline.shared.RuleViolation;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings › Notifications: which business events reach a team member by push, SMS and email
 * ({@code messaging.notification_prefs.matrix}), plus quiet hours. Unknown events or channels are rejected; events the
 * member never changed keep the design's defaults.
 */
public record NotificationMatrix(Map<String, Map<String, Boolean>> matrix, LocalTime quietFrom, LocalTime quietTo) {

    public static final List<String> CHANNELS = List.of("push", "sms", "email");
    public static final String UNKNOWN = "Pick events and channels from the table.";
    public static final LocalTime QUIET_FROM = LocalTime.of(21, 0);
    public static final LocalTime QUIET_TO = LocalTime.of(7, 0);

    /** Design 02 Settings › Notifications, row by row: push, SMS, email. */
    private static final Map<String, List<Boolean>> DEFAULTS = defaults();

    public NotificationMatrix {
        var copy = new LinkedHashMap<String, Map<String, Boolean>>();
        matrix.forEach((event, channels) ->
                copy.put(event, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(channels))));
        matrix = java.util.Collections.unmodifiableMap(copy);
    }

    public static List<String> events() {
        return List.copyOf(DEFAULTS.keySet());
    }

    public static NotificationMatrix defaultsOnly() {
        return withDefaults(Map.of(), QUIET_FROM, QUIET_TO);
    }

    /** Stored choices over the defaults, in the design's row order. */
    public static NotificationMatrix withDefaults(
            Map<String, Map<String, Boolean>> stored, LocalTime quietFrom, LocalTime quietTo) {
        var out = new LinkedHashMap<String, Map<String, Boolean>>();
        DEFAULTS.forEach((event, flags) -> {
            var row = new LinkedHashMap<String, Boolean>();
            var saved = stored.getOrDefault(event, Map.of());
            for (int i = 0; i < CHANNELS.size(); i++) {
                var channel = CHANNELS.get(i);
                row.put(channel, saved.getOrDefault(channel, flags.get(i)));
            }
            out.put(event, row);
        });
        return new NotificationMatrix(out, quietFrom, quietTo);
    }

    /** Validates an edit (every event and channel must be known) and fills what was left out. */
    public static NotificationMatrix edit(Map<String, Map<String, Boolean>> requested, NotificationMatrix current) {
        requested.forEach((event, channels) -> {
            if (!DEFAULTS.containsKey(event) || !CHANNELS.containsAll(channels.keySet())) {
                throw RuleViolation.of("matrix", "allowed", UNKNOWN);
            }
        });
        var merged = new LinkedHashMap<String, Map<String, Boolean>>();
        current.matrix().forEach((event, row) -> {
            var next = new LinkedHashMap<>(row);
            next.putAll(requested.getOrDefault(event, Map.of()));
            merged.put(event, next);
        });
        return new NotificationMatrix(merged, current.quietFrom(), current.quietTo());
    }

    private static Map<String, List<Boolean>> defaults() {
        var d = new LinkedHashMap<String, List<Boolean>>();
        d.put("new_booking", List.of(true, true, false));
        d.put("quote_request", List.of(true, true, true));
        d.put("customer_message", List.of(true, false, false));
        d.put("payout", List.of(true, false, true));
        d.put("dispute", List.of(true, true, true));
        d.put("low_stock", List.of(true, false, true));
        d.put("quality", List.of(false, false, true));
        return java.util.Collections.unmodifiableMap(d);
    }
}
