package ca.northline.developer.web;

import ca.northline.developer.domain.DeveloperRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request/response bodies of the developer endpoints. Secrets appear only in the create/rotate responses. */
final class DeveloperDtos {
    private DeveloperDtos() {}

    record IssueApiKeyRequest(
            @NotBlank(message = DeveloperRules.NAME_REQUIRED)
            @Size(max = DeveloperRules.NAME_MAX, message = DeveloperRules.NAME_TOO_LONG)
            String name,

            @NotEmpty(message = DeveloperRules.SCOPES_REQUIRED)
            List<String> scopes) {}

    record ApiKeyResponse(
            String id,
            String name,
            List<String> scopes,
            String prefix,
            int rateLimit,
            Instant createdAt,
            @Nullable Instant lastUsedAt) {}

    /** The only time the secret is returned. */
    record IssuedApiKeyResponse(ApiKeyResponse key, String secret) {}

    record AddWebhookRequest(
            @NotBlank(message = DeveloperRules.URL_REQUIRED) String url,

            @NotEmpty(message = DeveloperRules.EVENTS_REQUIRED)
            List<String> events) {}

    record WebhookResponse(
            String id,
            String url,
            List<String> events,
            boolean active,
            String signature,
            Instant createdAt,
            @Nullable Integer lastStatus,
            @Nullable Instant lastDeliveryAt,
            @Nullable Instant failingSince,
            @Nullable Instant disabledAt,
            @Nullable Instant previousSecretUntil) {}

    /** Rotate: how long the replaced secret keeps signing (null = 24 h, 0 = stops at once). */
    record RotateWebhookRequest(@Nullable Integer overlapHours) {}

    /** One row of the delivery log (S-33). */
    record WebhookDeliveryResponse(
            String id,
            String eventId,
            @Nullable String eventType,
            String state,
            int attempts,
            @Nullable Integer statusCode,
            @Nullable Instant lastAttemptAt,
            @Nullable Instant nextAttemptAt,
            @Nullable Integer durationMs,
            @Nullable String error,
            @Nullable String responseSnippet,
            boolean test,
            @Nullable String resendOf,
            Instant createdAt,
            List<WebhookAttemptResponse> history) {}

    record WebhookAttemptResponse(
            int attempt,
            Instant at,
            @Nullable Integer statusCode,
            @Nullable Integer durationMs,
            @Nullable String error,
            @Nullable String responseSnippet) {}

    /** The only time the signing secret is returned. */
    record WebhookWithSecretResponse(WebhookResponse endpoint, String secret) {}

    /** Choices for the create dialogs. */
    record DeveloperOptionsResponse(List<String> scopes, List<String> events) {}

    record AuditEntryResponse(
            String id,
            Instant at,
            @Nullable String actorId,
            @Nullable String actorName,
            @Nullable String role,
            String action,
            @Nullable String targetType,
            @Nullable String targetId) {}
}
