package ca.northline.developer.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Append-only record of privileged actions ({@code developer.audit_log}, "every delete is audit-logged"). Call it
 * inside the transaction that makes the change, so the entry commits with it. {@code before}/{@code after} hold ids
 * and codes only — never personal data.
 */
public interface AuditTrail {

    void record(Entry entry);

    /**
     * @param merchantId the business the action belongs to (Settings › Security › Audit log); {@code null} for a
     *     platform action that belongs to no business (S-127: Northline staff's agent tools)
     * @param action dotted verb, e.g. {@code team.role_changed}, {@code api_key.revoked}
     */
    record Entry(
            @Nullable String merchantId,
            String actorId,
            String role,
            String action,
            String targetType,
            String targetId,
            @Nullable Map<String, ?> before,
            @Nullable Map<String, ?> after) {

        public static Entry of(
                String merchantId, String actorId, String role, String action, String targetType, String targetId) {
            return new Entry(merchantId, actorId, role, action, targetType, targetId, null, null);
        }

        public Entry withChange(@Nullable Map<String, ?> newBefore, @Nullable Map<String, ?> newAfter) {
            return new Entry(merchantId, actorId, role, action, targetType, targetId, newBefore, newAfter);
        }
    }
}
