package ca.northline.ai.api;

import ca.northline.shared.security.MerchantPermission;
import ca.northline.shared.security.MerchantRole;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A tool the Studio assistant may call (S-130). Implement it as a Spring bean in the module that owns the data, over
 * that module's own use cases: the model then only ever sees what the caller could open in the Studio. The platform
 * offers a tool only to roles that hold {@link #permission()}, checks it again ({@code MerchantAccess}, fresh
 * membership and {@code acr=mfa}) before every run, and turns a refusal into an error the model reads.
 *
 * <p>Return ids, names, dates, amounts and states only — never contact details (email, phone, address), and never
 * data of another business: tools receive the authorized {@link Call#merchantId()} and must scope every read to it.
 */
public interface AssistantTool {

    /** snake_case, unique across modules ({@code list_orders}). */
    String name();

    /** What it returns, for the model (English). */
    String description();

    /** JSON Schema {@code properties} of the arguments ({@code {"state": {"type": "string", "enum": [...]}}}). */
    Map<String, Object> parameters();

    /** Required argument names. */
    default List<String> required() {
        return List.of();
    }

    /** The narrowest permission the matching Studio screen needs. */
    MerchantPermission permission();

    /** The Studio screen key the data belongs to ({@code orders}, {@code earnings}), or null. */
    default @Nullable String screen() {
        return null;
    }

    /** True for a change (a write): never run by the model, only after the person confirms {@link #preview}. */
    default boolean write() {
        return false;
    }

    /** For a write: what will happen, in the caller's language ("Mark order NL-48213 as packed"). */
    default String preview(Call call) {
        return name();
    }

    /** Runs the tool as the caller. Throw the usual domain errors (NotFound, RuleViolation…): the model reads them. */
    Result run(Call call);

    /** The authorized business, the caller and the model's arguments. */
    record Call(String merchantId, String userId, MerchantRole role, JsonNode args, Locale locale) {
        public @Nullable String text(String key) {
            var v = args.get(key);
            return v == null || v.isNull() ? null : v.asString();
        }

        public int integer(String key, int fallback, int min, int max) {
            var v = args.get(key);
            var n = v != null && v.isNumber() ? v.asInt() : fallback;
            return Math.clamp(n, min, max);
        }
    }

    /**
     * @param data serialised to JSON for the model
     * @param summary one line for the person ("orders to pack → 4")
     */
    record Result(Object data, String summary) {}
}
