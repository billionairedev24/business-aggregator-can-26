package ca.northline.auth.application;

import ca.northline.auth.domain.PendingFederation;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Finishes a "Continue with Google/Apple" once the Northline side is done in the same browser (S-18): the second factor
 * of the matching existing account was used (sign-in), or a new account was created (registration). Only then is the
 * provider account linked — a verified email alone never links, so a Google account can't take over a Northline
 * account without its passkey / authenticator. Another person signing in in between drops the pending link.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FederatedLinking {

    private final FlowStore flow;
    private final FederatedIdentities identities;
    private final AuditTrail audit;
    private final Clock clock;

    /** In the sign-in / registration transaction, after the account is known. */
    public void complete(String userId, boolean newAccount) {
        var pending = flow.get(FlowStore.FEDERATION).orElse(null);
        if (pending == null) {
            return;
        }
        flow.remove(FlowStore.FEDERATION);
        var now = clock.instant();
        if (pending.linked() && userId.equals(pending.userId())) {
            identities.used(pending.provider(), pending.subject(), pending.email(), pending.privateRelay(), now);
            return;
        }
        var forThisPerson = newAccount ? pending.userId() == null : userId.equals(pending.userId());
        if (!forThisPerson || pending.linked()) {
            log.info("Pending {} sign-in not linked: another account signed in", pending.provider());
            return;
        }
        identities.link(pending.provider(), pending.subject(), userId, pending.email(), pending.privateRelay(), now);
        audit.record(
                userId,
                "auth.federated_linked",
                "user",
                userId,
                Map.of("provider", pending.provider(), "newAccount", newAccount));
        log.info("{} account linked to user {}", pending.provider(), userId);
    }

    void remember(PendingFederation pending) {
        flow.put(FlowStore.FEDERATION, pending);
    }
}
