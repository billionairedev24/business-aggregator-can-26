package ca.northline.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The event's type on the wire (Kafka header {@code nl-event-type}) and the name of its JSON Schema
 * ({@code events/<type>.v<version>.schema.json}), when it isn't the default {@code <topic module>.<snake_case record
 * name>} — e.g. {@code MenuItemAvailabilityChanged} on {@code food.menu} is {@code food.item_availability}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventType {
    String value();
}
