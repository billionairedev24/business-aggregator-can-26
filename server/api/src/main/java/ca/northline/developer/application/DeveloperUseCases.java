package ca.northline.developer.application;

import ca.northline.developer.domain.ApiKey;
import ca.northline.developer.domain.AuditRecord;
import ca.northline.developer.domain.WebhookEndpoint;
import java.util.List;

/** Inbound ports of Settings › API &amp; integrations and the audit log (one small interface each). */
public final class DeveloperUseCases {
    private DeveloperUseCases() {}

    /** Who acts: the team member (id + role code) for the audit log. */
    public record Actor(String merchantId, String userId, String role) {}

    public interface ListApiKeys {
        /** Active keys, oldest first. */
        List<ApiKey> list(String merchantId);
    }

    public interface IssueApiKey {
        record Command(Actor actor, String name, List<String> scopes) {}

        ApiKey.Issued issue(Command command);
    }

    public interface RevokeApiKey {
        void revoke(Actor actor, String keyId);
    }

    public interface ListWebhookEndpoints {
        List<WebhookEndpoint> endpoints(String merchantId);
    }

    public interface AddWebhookEndpoint {
        record Command(Actor actor, String url, List<String> events) {}

        WebhookEndpoint.WithSecret add(Command command);
    }

    public interface RotateWebhookSecret {
        WebhookEndpoint.WithSecret rotate(Actor actor, String endpointId);
    }

    public interface RemoveWebhookEndpoint {
        void remove(Actor actor, String endpointId);
    }

    public interface ViewAuditLog {
        int DAYS = 90;
        int LIMIT = 200;

        /** The business's audit entries of the last {@link #DAYS} days, newest first. */
        List<AuditRecord> recent(String merchantId);
    }
}
