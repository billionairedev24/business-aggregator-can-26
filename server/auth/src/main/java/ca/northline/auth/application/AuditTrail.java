package ca.northline.auth.application;

import java.util.Map;

/** Outbound port: {@code developer.audit_log} rows for security changes made by the person themselves (S-19). */
public interface AuditTrail {

    /** In the caller's transaction: the change and its audit row commit together. */
    void record(String actorId, String action, String targetType, String targetId, Map<String, ?> after);
}
