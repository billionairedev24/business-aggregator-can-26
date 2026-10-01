package ca.northline.developer.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reading {@code developer.audit_log} for the console's audit log viewer (S-96): filters, newest first, by page. */
public interface AuditLogQuery {

    Page search(Filter filter, int limit);

    /**
     * @param action an exact action or a prefix ending in {@code .} ({@code payments.} = every payments action)
     * @param before the cursor: entries strictly older than this (at, id)
     */
    record Filter(
            @Nullable String actorId,
            @Nullable String action,
            @Nullable String merchantId,
            @Nullable String targetId,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable Cursor before) {}

    record Cursor(Instant at, String id) {}

    record Entry(
            String id,
            Instant at,
            @Nullable String actorId,
            @Nullable String role,
            String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable String merchantId,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after) {}

    record Page(List<Entry> items, @Nullable Cursor next) {

        public Page {
            items = List.copyOf(items);
        }
    }
}
