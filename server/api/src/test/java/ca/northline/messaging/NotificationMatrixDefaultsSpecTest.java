package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.messaging.domain.CustomerNotificationPrefs;
import ca.northline.messaging.domain.NotificationMatrix;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-27: the worker's notifications consumer reads the Settings › Notifications defaults from
 * {@code docs/spec/notification-matrix-defaults.json}; the api's {@link NotificationMatrix} must say the same.
 */
class NotificationMatrixDefaultsSpecTest {

    @Test
    void apiDefaultsEqualTheSpecFile() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("spec/notification-matrix-defaults.json")) {
            assertThat(in).isNotNull();
            var spec = JsonMapper.builder().build().readTree(in);
            var rows = new LinkedHashMap<String, Map<String, Boolean>>();
            for (var event : spec.get("rows").propertyNames()) {
                var row = new LinkedHashMap<String, Boolean>();
                for (var channel : spec.get("rows").get(event).propertyNames()) {
                    row.put(channel, spec.get("rows").get(event).get(channel).asBoolean());
                }
                rows.put(event, row);
            }
            var defaults = NotificationMatrix.defaultsOnly();
            assertThat(defaults.matrix()).containsExactlyEntriesOf(rows);
            assertThat(spec.get("channels").valueStream().map(n -> n.asString()).toList())
                    .isEqualTo(NotificationMatrix.CHANNELS);
            assertThat(LocalTime.parse(spec.get("quietHours").get("from").asString()))
                    .isEqualTo(NotificationMatrix.QUIET_FROM);
            assertThat(LocalTime.parse(spec.get("quietHours").get("to").asString()))
                    .isEqualTo(NotificationMatrix.QUIET_TO);
        }
    }

    /** S-102: the worker's customer notifications read the "customer" section; the api's account page must agree. */
    @Test
    void customerDefaultsEqualTheSpecFile() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("spec/notification-matrix-defaults.json")) {
            assertThat(in).isNotNull();
            var spec = JsonMapper.builder().build().readTree(in).get("customer");
            var rows = new LinkedHashMap<String, Map<String, Boolean>>();
            for (var event : spec.get("rows").propertyNames()) {
                var row = new LinkedHashMap<String, Boolean>();
                for (var channel : spec.get("rows").get(event).propertyNames()) {
                    row.put(channel, spec.get("rows").get(event).get(channel).asBoolean());
                }
                rows.put(event, row);
            }
            var defaults = CustomerNotificationPrefs.defaultsOnly();
            assertThat(defaults.matrix()).containsExactlyEntriesOf(rows);
            assertThat(LocalTime.parse(spec.get("quietHours").get("from").asString()))
                    .isEqualTo(defaults.quietFrom());
            assertThat(LocalTime.parse(spec.get("quietHours").get("to").asString()))
                    .isEqualTo(defaults.quietTo());
        }
    }
}
