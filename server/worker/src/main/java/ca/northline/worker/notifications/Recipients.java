package ca.northline.worker.notifications;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Outbound port: who is on a business's team and how to reach them — identity (contact, language), merchants
 * (membership, business name) and messaging (preferences). Adapter: {@link JdbcRecipients}, a read-only view of the
 * api's tables (docs/DECISIONS.md § S-27).
 */
public interface Recipients {

    /** Active members of {@code merchantId} whose role is in {@code roles}. */
    List<Recipient> of(String merchantId, Set<String> roles);

    /** One member again (a deferred notification), empty when they left the team or the account isn't active. */
    Optional<Recipient> member(String merchantId, String userId);

    Optional<String> businessName(String merchantId);
}
