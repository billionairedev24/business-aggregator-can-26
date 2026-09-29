package ca.northline.developer.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One audit log row as the owner sees it (Settings › Security › Audit log). */
public record AuditRecord(
        String id,
        Instant at,
        @Nullable String actorId,
        @Nullable String role,
        String action,
        @Nullable String targetType,
        @Nullable String targetId) {}
