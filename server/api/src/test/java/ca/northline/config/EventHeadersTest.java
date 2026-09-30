package ca.northline.config;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.api.MerchantRenamed;
import ca.northline.payments.api.PayoutFailed;
import ca.northline.platform.EventHeaders;
import ca.northline.shared.DomainEvent;
import ca.northline.shared.ExternalizedTopicsCatalogueTest;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Every externalized event has a wire type whose JSON Schema exists (the worker validates against it, S-26). */
class EventHeadersTest {

    @Test
    void typesFollowTheSchemaFileNames() {
        assertThat(EventHeaders.type(PayoutFailed.class)).isEqualTo("payments.payout_failed");
        assertThat(EventHeaders.type(MerchantRenamed.class)).isEqualTo("merchants.merchant_renamed");
        assertThat(EventHeaders.type(ca.northline.booking.api.BookingProgressed.BookingEnRoute.class))
                .isEqualTo("booking.booking_en_route");
        assertThat(EventHeaders.type(ca.northline.food.api.MenuItemAvailabilityChanged.class))
                .isEqualTo("food.item_availability");
    }

    @Test
    void everyExternalizedEventHasTheSchemaOfItsTypeAndVersion() throws Exception {
        var missing = new ArrayList<String>();
        for (var name : ExternalizedTopicsCatalogueTest.externalizedTopics().keySet()) {
            var type = Class.forName(name);
            var version = versionOf(type);
            var schema = "events/%s.v%d.schema.json".formatted(EventHeaders.type(type), version);
            if (getClass().getClassLoader().getResource(schema) == null) {
                missing.add(name + " → " + schema);
            }
        }
        assertThat(missing).as("server/api/src/main/resources/events").isEmpty();
    }

    @Test
    void headersCarryIdTypeAndVersion() {
        var event = new PayoutFailed("01J9ZD3V00000000000000EVT1", Instant.EPOCH, "po_1", "m_1", "failed", 100, null);
        assertThat(EventHeaders.of(event))
                .containsEntry(EventHeaders.ID, "01J9ZD3V00000000000000EVT1")
                .containsEntry(EventHeaders.TYPE, "payments.payout_failed")
                .containsEntry(EventHeaders.VERSION, "1");
    }

    /** {@code version()} of an instance built from default component values (records have no other way). */
    private static int versionOf(Class<?> type) throws Exception {
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (var i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = defaultValue(components[i]);
        }
        var ctor = type.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ((DomainEvent) ctor.newInstance(values)).version();
    }

    private static Object defaultValue(RecordComponent c) {
        var t = c.getType();
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == boolean.class) return false;
        if (t == double.class) return 0d;
        if (t == String.class) return "x";
        if (t == Instant.class) return Instant.EPOCH;
        if (t == java.util.List.class) return java.util.List.of();
        if (t == java.util.Set.class) return java.util.Set.of();
        if (t == java.util.Map.class) return java.util.Map.of();
        if (t.isEnum()) return t.getEnumConstants()[0];
        return null;
    }
}
