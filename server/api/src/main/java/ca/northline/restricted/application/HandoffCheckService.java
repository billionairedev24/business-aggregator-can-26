package ca.northline.restricted.application;

import ca.northline.restricted.api.AgeChecksReport;
import ca.northline.restricted.api.HandoffChecks;
import ca.northline.shared.Ids;
import ca.northline.shared.RuleViolation;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link HandoffChecks}: a passed check needs all three confirmations; a refusal needs a reason. Joins the caller's
 * transaction, so the record commits with the handoff or the refusal it is about. {@link AgeChecksReport} reads them.
 */
@Service
@RequiredArgsConstructor
class HandoffCheckService implements HandoffChecks, AgeChecksReport {

    private static final Set<String> ROLES = Set.of("courier", "merchant");
    private static final Set<String> PLACES = Set.of("door", "counter");
    private static final int RECENT = 50;

    private final HandoffCheckStore store;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String record(Check check) {
        if (!ROLES.contains(check.actorRole()) || !PLACES.contains(check.place())) {
            throw new IllegalArgumentException("Unknown handoff role or place: " + check);
        }
        var reason = check.reason();
        if (reason == null) {
            if (!check.idChecked() || !check.recipientMatches() || !check.ofAge()) {
                throw RuleViolation.of("idCheck", "required", CONFIRM);
            }
        } else if (!REASONS.contains(reason)) {
            throw RuleViolation.of("reason", "required", REASON);
        }
        var id = Ids.next();
        store.insert(id, check);
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public Report report(Instant from, Instant to, @Nullable String province) {
        return new Report(
                from,
                to,
                province,
                store.verifications(from, to),
                store.failures(from, to),
                store.handoffs(from, to, province),
                store.refusals(from, to, province),
                store.byPlace(from, to, province),
                store.recentRefusals(from, to, province, RECENT));
    }
}
