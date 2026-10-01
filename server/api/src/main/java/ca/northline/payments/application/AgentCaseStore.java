package ca.northline.payments.application;

import ca.northline.payments.api.AgentCases.AgentDecision;
import ca.northline.payments.api.AgentCases.Summary;
import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-80): the agents' queue over disputes and refund cases, and {@code payments.agent_decisions}. */
public interface AgentCaseStore {

    /** A case in the queue: {@code kind} = {@code dispute | refund}. */
    record CaseRef(String kind, String id) {}

    List<CaseRef> queue(MerchantScope scope, Instant decidedSince, int limit);

    Summary summary(MerchantScope scope, Instant weekAgo);

    void insert(String kind, String caseId, String merchantId, AgentDecision decision, String role);

    Optional<StoredDecision> find(String decisionId);

    /** The decision waiting for a co-sign on a case, if any. */
    Optional<AgentDecision> pending(String kind, String caseId);

    /** The latest applied decision on a case, if any. */
    Optional<AgentDecision> applied(String kind, String caseId);

    /** Records the co-signer; false when the decision no longer waits. */
    boolean cosign(String decisionId, String state, String staffId, String role, @Nullable String note, Instant at);

    int customerDisputes(String customerId, String exceptDisputeId);

    /** {count, won}: the business's other disputes and how many were released to it. */
    int[] merchantDisputes(String merchantId, String exceptDisputeId);

    record StoredDecision(String kind, String caseId, String merchantId, AgentDecision decision) {}
}
