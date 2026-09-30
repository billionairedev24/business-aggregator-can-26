package ca.northline.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Read-only view of a Stripe object from a webhook ({@code data.object}): the few fields the handlers read, by path.
 * {@link #redacted()} is what is stored — personal data Stripe includes (billing details, e-mail, names, addresses,
 * dispute evidence) is dropped because Northline never needs it.
 */
public final class StripeObject {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Keys removed anywhere in the object before it is stored. */
    static final Set<String> PERSONAL = Set.of(
            "billing_details",
            "receipt_email",
            "shipping",
            "customer_details",
            "evidence",
            "individual",
            "representative",
            "email",
            "phone",
            "name",
            "address",
            "account_holder_name",
            "dob",
            "id_number",
            "ssn_last_4",
            "support_phone",
            "support_email",
            "support_address");

    private final JsonNode node;

    private StripeObject(JsonNode node) {
        this.node = node;
    }

    public static StripeObject parse(String json) {
        return new StripeObject(JSON.readTree(json));
    }

    public @Nullable String id() {
        return text("id");
    }

    public @Nullable String text(String... path) {
        var n = at(path);
        return n == null || n.isNull() ? null : n.asString();
    }

    public @Nullable Long number(String... path) {
        var n = at(path);
        return n == null || !n.isNumber() ? null : n.asLong();
    }

    public boolean flag(String... path) {
        var n = at(path);
        return n != null && n.isBoolean() && n.asBoolean();
    }

    /** Unix seconds → instant. */
    public @Nullable Instant time(String... path) {
        var seconds = number(path);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    public List<String> strings(String... path) {
        var n = at(path);
        if (n == null || !n.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(n.spliterator(), false)
                .map(JsonNode::asString)
                .toList();
    }

    /** The objects of an array (e.g. {@code external_accounts.data}). */
    public List<StripeObject> objects(String... path) {
        var n = at(path);
        if (n == null || !n.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(n.spliterator(), false)
                .map(StripeObject::new)
                .toList();
    }

    /** The object as stored: personal fields removed. */
    public String redacted() {
        var copy = node.deepCopy();
        strip(copy);
        return JSON.writeValueAsString(copy);
    }

    private static void strip(JsonNode n) {
        if (n instanceof ObjectNode object) {
            object.remove(PERSONAL);
            object.forEach(StripeObject::strip);
        } else if (n.isArray()) {
            n.forEach(StripeObject::strip);
        }
    }

    private @Nullable JsonNode at(String... path) {
        JsonNode n = node;
        for (var key : path) {
            if (n == null || !n.isObject()) {
                return null;
            }
            n = n.get(key);
        }
        return n;
    }
}
