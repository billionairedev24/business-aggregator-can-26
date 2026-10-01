package ca.northline.messaging.domain;

import ca.northline.shared.RuleViolation;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A customer's notification choices (design 06 account › notifications): which kind of update reaches them by push,
 * SMS and email, quiet hours, the language notifications are written in and marketing email (CASL). Security alerts
 * always go to every channel and can't be turned off. Stored next to the Studio member matrix of the same person
 * ({@code messaging.notification_prefs}); quiet hours are the person's, shared by both.
 *
 * @param language {@code app | en | fr} ("Same as app", English, Français)
 * @param marketing {@code weekly | rewards | none} ("Weekly digest", "Only rewards I'm eligible for", "None (CASL
 *     opt-out)")
 */
public record CustomerNotificationPrefs(
        Map<String, Map<String, Boolean>> matrix,
        boolean quietOn,
        LocalTime quietFrom,
        LocalTime quietTo,
        String language,
        String marketing) {

    public static final List<String> CHANNELS = List.of("push", "sms", "email");
    public static final String SECURITY = "security";
    public static final String CHOOSE = "Choose from the list.";
    public static final String LOCKED = "Security alerts always go to every channel.";

    /** Design 06's rows and their defaults: push, SMS, email. */
    private static final Map<String, List<Boolean>> DEFAULTS = defaults();

    /** The selects' choices: 9, 10 or 11 pm to 6, 7 or 8 am. */
    public static final Set<LocalTime> FROM = Set.of(LocalTime.of(21, 0), LocalTime.of(22, 0), LocalTime.of(23, 0));

    public static final Set<LocalTime> TO = Set.of(LocalTime.of(6, 0), LocalTime.of(7, 0), LocalTime.of(8, 0));
    public static final Set<String> LANGUAGES = Set.of("app", "en", "fr");
    public static final Set<String> MARKETING = Set.of("weekly", "rewards", "none");

    public CustomerNotificationPrefs {
        var copy = new LinkedHashMap<String, Map<String, Boolean>>();
        matrix.forEach((k, v) -> copy.put(k, Collections.unmodifiableMap(new LinkedHashMap<>(v))));
        matrix = Collections.unmodifiableMap(copy);
    }

    public static List<String> events() {
        return List.copyOf(DEFAULTS.keySet());
    }

    /** Stored choices over the defaults, in the design's row order; security alerts forced on. */
    public static CustomerNotificationPrefs of(
            Map<String, Map<String, Boolean>> stored,
            @Nullable Boolean quietOn,
            @Nullable LocalTime quietFrom,
            @Nullable LocalTime quietTo,
            @Nullable String language,
            @Nullable String marketing) {
        var out = new LinkedHashMap<String, Map<String, Boolean>>();
        DEFAULTS.forEach((event, flags) -> {
            var row = new LinkedHashMap<String, Boolean>();
            var saved = stored.getOrDefault(event, Map.of());
            for (int i = 0; i < CHANNELS.size(); i++) {
                var channel = CHANNELS.get(i);
                row.put(channel, SECURITY.equals(event) || saved.getOrDefault(channel, flags.get(i)));
            }
            out.put(event, row);
        });
        return new CustomerNotificationPrefs(
                out,
                quietOn == null || quietOn,
                quietFrom == null ? LocalTime.of(22, 0) : quietFrom,
                quietTo == null ? LocalTime.of(7, 0) : quietTo,
                language == null ? "app" : language,
                marketing == null ? "weekly" : marketing);
    }

    public static CustomerNotificationPrefs defaultsOnly() {
        return of(Map.of(), null, null, null, null, null);
    }

    /** An edit: changed cells and settings only; anything unknown is a 422. */
    public CustomerNotificationPrefs edit(
            @Nullable Map<String, Map<String, Boolean>> cells,
            @Nullable Boolean quiet,
            @Nullable LocalTime from,
            @Nullable LocalTime to,
            @Nullable String lang,
            @Nullable String mkt) {
        var merged = new LinkedHashMap<String, Map<String, Boolean>>();
        matrix.forEach((k, v) -> merged.put(k, new LinkedHashMap<>(v)));
        if (cells != null) {
            cells.forEach((event, channels) -> {
                if (!DEFAULTS.containsKey(event) || !CHANNELS.containsAll(channels.keySet())) {
                    throw RuleViolation.of("matrix", "allowed", NotificationMatrix.UNKNOWN);
                }
                if (SECURITY.equals(event) && channels.containsValue(false)) {
                    throw RuleViolation.of("matrix", "locked", LOCKED);
                }
                merged.computeIfAbsent(event, _ -> new LinkedHashMap<>()).putAll(channels);
            });
        }
        if (from != null && !FROM.contains(from)) {
            throw RuleViolation.of("quietFrom", "allowed", CHOOSE);
        }
        if (to != null && !TO.contains(to)) {
            throw RuleViolation.of("quietTo", "allowed", CHOOSE);
        }
        if (lang != null && !LANGUAGES.contains(lang)) {
            throw RuleViolation.of("language", "allowed", CHOOSE);
        }
        if (mkt != null && !MARKETING.contains(mkt)) {
            throw RuleViolation.of("marketing", "allowed", CHOOSE);
        }
        return new CustomerNotificationPrefs(
                merged,
                quiet == null ? quietOn : quiet,
                from == null ? quietFrom : from,
                to == null ? quietTo : to,
                lang == null ? language : lang,
                mkt == null ? marketing : mkt);
    }

    private static Map<String, List<Boolean>> defaults() {
        var d = new LinkedHashMap<String, List<Boolean>>();
        d.put("booking_reminders", List.of(true, true, false));
        d.put("order_updates", List.of(true, false, true));
        d.put("sign_off", List.of(true, true, true));
        d.put("quotes_messages", List.of(true, false, false));
        d.put("refunds_cases", List.of(true, false, true));
        d.put("offers", List.of(true, false, false));
        d.put(SECURITY, List.of(true, true, true));
        return Collections.unmodifiableMap(d);
    }
}
