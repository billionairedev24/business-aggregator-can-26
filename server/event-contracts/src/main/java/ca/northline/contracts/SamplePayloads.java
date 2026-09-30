package ca.northline.contracts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Check 3: builds each event record with representative values, serialises it the way the outbox does (Jackson 3,
 * nulls included — {@code spring.jackson.default-property-inclusion: always}) and validates the JSON against the
 * schema of its type and version. Values follow the schema where it constrains them (enum, pattern, format, minimum,
 * lengths), so what fails is a real divergence: a field the schema doesn't know (closed schemas), one it requires that
 * the record lacks, a type that differs, a nullable component the schema declares non-null (a second sample sets
 * every {@code @Nullable} component to null), or a Java enum constant outside the schema's {@code enum} (one sample
 * per constant).
 */
public final class SamplePayloads {

    static final Instant AT = Instant.parse("2026-09-30T18:00:00Z");
    /** Tried in order for a {@code pattern}: a ULID, case numbers, a tax period, then plain text. */
    static final List<String> PATTERN_CANDIDATES =
            List.of("01J9ZD3V00000000000000EVT1", "RF-2214", "DS-1188", "2026-Q3", "CAD", "sample", "x");

    private final JsonMapper json;

    public SamplePayloads(JsonMapper json) {
        this.json = json;
    }

    /** A named sample (what was varied) and its JSON. */
    public record Sample(String label, JsonNode payload) {}

    /** The samples of {@code type} for {@code schema}; construction problems are thrown as {@link SampleException}. */
    public List<Sample> samples(Class<?> type, JsonNode schema) {
        var samples = new ArrayList<Sample>();
        samples.add(new Sample("all fields set", json.valueToTree(build(type, schema, Variant.FULL))));
        if (hasNullable(type)) {
            samples.add(new Sample("@Nullable fields null", json.valueToTree(build(type, schema, Variant.NULLS))));
        }
        for (var component : type.getRecordComponents()) {
            if (component.getType().isEnum()) {
                for (var constant : component.getType().getEnumConstants()) {
                    samples.add(new Sample(
                            component.getName() + " = " + constant,
                            json.valueToTree(build(type, schema, new Variant(false, component.getName(), constant)))));
                }
            }
        }
        return samples;
    }

    /** {@code version()} of an event record (api {@code DomainEvent}s), 1 for events without one. */
    static int version(Class<?> type) {
        try {
            var method = type.getMethod("version");
            var event = build(type, null, Variant.FULL);
            return (int) method.invoke(event);
        } catch (NoSuchMethodException e) {
            return 1;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new SampleException(type.getSimpleName() + ".version() failed: " + e);
        }
    }

    /** Which sample: nullables null or set, and a fixed value for one component. */
    record Variant(
            boolean nulls,
            @Nullable String component,
            @Nullable Object value) {
        static final Variant FULL = new Variant(false, null, null);
        static final Variant NULLS = new Variant(true, null, null);
    }

    static Object build(Class<?> type, @Nullable JsonNode schema, Variant variant) {
        if (!type.isRecord()) {
            throw new SampleException(type.getName() + " is not a record");
        }
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        @Nullable Object[] values = new @Nullable Object[components.length];
        for (var i = 0; i < components.length; i++) {
            var c = components[i];
            types[i] = c.getType();
            var property = schema == null ? null : schema.path("properties").get(c.getName());
            if (c.getName().equals(variant.component())) {
                values[i] = variant.value();
            } else if (variant.nulls() && nullable(c)) {
                values[i] = null;
            } else {
                values[i] = value(c.getGenericType(), property, c.getName());
            }
        }
        try {
            var constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (InvocationTargetException e) {
            throw new SampleException(
                    "the sample was refused by " + type.getSimpleName() + "'s constructor: " + e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new SampleException("can't construct " + type.getName() + ": " + e);
        }
    }

    static @Nullable Object value(Type type, @Nullable JsonNode schema, String name) {
        var raw = raw(type);
        if (raw == String.class) {
            return string(schema, name);
        }
        if (raw == int.class || raw == Integer.class) {
            return (int) number(schema);
        }
        if (raw == long.class || raw == Long.class) {
            return number(schema);
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            return raw == float.class || raw == Float.class ? (Object) 1.5f : (Object) 1.5d;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return true;
        }
        if (raw == BigDecimal.class) {
            return new BigDecimal("12.50");
        }
        if (raw == Instant.class) {
            return AT;
        }
        if (raw == OffsetDateTime.class) {
            return AT.atOffset(java.time.ZoneOffset.UTC);
        }
        if (raw == LocalDate.class) {
            return LocalDate.of(2026, 9, 30);
        }
        if (raw == LocalTime.class) {
            return LocalTime.of(9, 30);
        }
        if (raw == Duration.class) {
            return Duration.ofMinutes(90);
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (Collection.class.isAssignableFrom(raw)) {
            var element = type instanceof ParameterizedType p ? p.getActualTypeArguments()[0] : String.class;
            var one = value(element, schema == null ? null : schema.get("items"), name);
            return Set.class.isAssignableFrom(raw) ? Set.of(one) : List.of(one);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return Map.of();
        }
        if (raw.isRecord()) {
            return build(raw, schema, Variant.FULL);
        }
        throw new SampleException("no sample for " + type.getTypeName() + " (" + name + "): teach SamplePayloads");
    }

    private static String string(@Nullable JsonNode schema, String name) {
        if (schema != null && schema.path("enum").isArray()) {
            for (var option : schema.get("enum")) {
                if (option.isString()) {
                    return option.asString();
                }
            }
        }
        var format = schema == null ? "" : schema.path("format").asString("");
        if (format.equals("date-time")) {
            return AT.toString();
        }
        if (format.equals("date")) {
            return "2026-09-30";
        }
        var min = schema == null ? 0 : schema.path("minLength").asInt(0);
        var max = schema == null ? Integer.MAX_VALUE : schema.path("maxLength").asInt(Integer.MAX_VALUE);
        if (schema != null && schema.has("pattern")) {
            var pattern = java.util.regex.Pattern.compile(schema.get("pattern").asString());
            return PATTERN_CANDIDATES.stream()
                    .filter(c -> pattern.matcher(c).find() && c.length() >= min && c.length() <= max)
                    .findFirst()
                    .orElseThrow(() -> new SampleException("no sample string matches " + name + "'s pattern " + pattern
                            + ": add one to " + "SamplePayloads.PATTERN_CANDIDATES"));
        }
        var text = "sample " + name;
        if (text.length() < min) {
            text = text + "x".repeat(min - text.length());
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static long number(@Nullable JsonNode schema) {
        if (schema != null
                && schema.path("enum").isArray()
                && !schema.get("enum").isEmpty()) {
            return schema.get("enum").get(0).asLong();
        }
        var minimum = schema != null && schema.has("minimum")
                ? (long) Math.ceil(schema.get("minimum").asDouble())
                : 0;
        return Math.max(minimum, 1);
    }

    private static Class<?> raw(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> (Class<?>) p.getRawType();
            default -> Object.class;
        };
    }

    static boolean hasNullable(Class<?> type) {
        for (var c : type.getRecordComponents()) {
            if (nullable(c)) {
                return true;
            }
        }
        return false;
    }

    /** {@code @org.jspecify.annotations.Nullable} on the component's type (TYPE_USE). */
    static boolean nullable(RecordComponent c) {
        return !c.getType().isPrimitive()
                && (c.getAnnotatedType().isAnnotationPresent(Nullable.class) || c.isAnnotationPresent(Nullable.class));
    }

    /** A sample couldn't be built — reported as a problem of the event, never skipped. */
    public static final class SampleException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SampleException(String message) {
            super(message);
        }
    }
}
