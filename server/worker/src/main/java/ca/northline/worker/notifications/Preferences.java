package ca.northline.worker.notifications;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One member's Settings › Notifications: the channel matrix over the design's defaults
 * ({@code docs/spec/notification-matrix-defaults.json}, the same file the api's {@code NotificationMatrix} is checked
 * against) and the quiet hours, in {@code zone}: the time zone of the business the notification is about (its
 * market's, else its province's; region model, S-134).
 */
public record Preferences(
        Map<String, Map<String, Boolean>> matrix, LocalTime quietFrom, LocalTime quietTo, ZoneId zone) {

    public static final String DEFAULTS = "spec/notification-matrix-defaults.json";

    public Preferences {
        matrix = Map.copyOf(matrix);
    }

    /** Whether the member gets {@code row} on {@code channel}; unknown rows and channels: no. */
    public boolean wants(String row, Channel channel) {
        return matrix.getOrDefault(row, Map.of()).getOrDefault(channel.code(), false);
    }

    /** Inside the quiet hours at {@code now} (local time in {@code zone}; an interval may wrap midnight, 21:00–07:00). */
    public boolean quietAt(Instant now) {
        if (quietFrom.equals(quietTo)) {
            return false;
        }
        var time = now.atZone(zone).toLocalTime();
        return quietFrom.isBefore(quietTo)
                ? !time.isBefore(quietFrom) && time.isBefore(quietTo)
                : !time.isBefore(quietFrom) || time.isBefore(quietTo);
    }

    /** The first moment after {@code now} when the quiet hours end. */
    public Instant quietEndsAfter(Instant now) {
        var local = now.atZone(zone);
        ZonedDateTime end = local.with(quietTo);
        if (!end.isAfter(local)) {
            end = end.plusDays(1);
        }
        return end.toInstant();
    }

    /** The design's defaults, read once from the spec file packaged into the worker. */
    public record Defaults(Map<String, Map<String, Boolean>> matrix, LocalTime quietFrom, LocalTime quietTo) {

        /** The team members' defaults (design 02, Settings › Notifications). */
        public static Defaults load(JsonMapper json) {
            return of(spec(json));
        }

        /** The customers' defaults (design 06, Account › Notifications; S-102). */
        public static Defaults customers(JsonMapper json) {
            return of(spec(json).get("customer"));
        }

        /** Never quiet, nothing governed by the matrix: a courier's run notices (S-102). */
        public static Preferences unconditional(ZoneId zone) {
            return new Preferences(Map.of(), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, zone);
        }

        private static JsonNode spec(JsonMapper json) {
            try (var in = Preferences.class.getClassLoader().getResourceAsStream(DEFAULTS)) {
                if (in == null) {
                    throw new IllegalStateException("classpath:" + DEFAULTS + " is missing");
                }
                return json.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static Defaults of(JsonNode spec) {
            var rows = new LinkedHashMap<String, Map<String, Boolean>>();
            spec.get("rows").properties().forEach(row -> rows.put(row.getKey(), flags(row.getValue())));
            return new Defaults(
                    rows,
                    LocalTime.parse(spec.get("quietHours").get("from").asString()),
                    LocalTime.parse(spec.get("quietHours").get("to").asString()));
        }

        /** Stored cells over the defaults (a member who never saved has no row; new rows get their defaults). */
        public Preferences with(
                Map<String, Map<String, Boolean>> stored, LocalTime storedFrom, LocalTime storedTo, ZoneId zone) {
            var merged = new LinkedHashMap<String, Map<String, Boolean>>();
            matrix.forEach((row, flags) -> {
                var cells = new LinkedHashMap<>(flags);
                cells.putAll(stored.getOrDefault(row, Map.of()));
                merged.put(row, cells);
            });
            return new Preferences(merged, storedFrom, storedTo, zone);
        }

        public Preferences only(ZoneId zone) {
            return new Preferences(matrix, quietFrom, quietTo, zone);
        }

        private static Map<String, Boolean> flags(JsonNode row) {
            var flags = new LinkedHashMap<String, Boolean>();
            row.properties()
                    .forEach(cell -> flags.put(cell.getKey(), cell.getValue().asBoolean()));
            return flags;
        }
    }
}
