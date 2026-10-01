package ca.northline.console.application;

import ca.northline.payments.api.AgentCases;
import ca.northline.payments.api.AgentCases.CaseDetail;
import ca.northline.payments.api.AgentCases.CaseRow;
import ca.northline.payments.api.AgentCases.EvidenceFile;
import ca.northline.payments.api.AgentCases.Summary;
import ca.northline.shared.MerchantScope;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Platform console — disputes &amp; refunds (S-80, design 03 {@code disputes}): the agents' queue across payments (the
 * cases, evidence and decisions) with the business's name, province and quality score (merchants, region, trust).
 */
public interface DisputeDesk {

    Queue queue(MerchantScope scope);

    Optional<Detail> detail(String kind, String caseId);

    Item decide(AgentCases.Decide command);

    Item cosign(AgentCases.Cosign command);

    Optional<EvidenceFile> evidence(String disputeId, String evidenceId);

    record Queue(Summary summary, List<Item> items) {
        public Queue {
            items = List.copyOf(items);
        }
    }

    /** A case with the business it is about. */
    record Item(CaseRow row, String businessName, @Nullable String province) {}

    /** @param sellerQuality the business's latest quality score (0–100), when it has one */
    record Detail(Item item, CaseDetail detail, @Nullable Integer sellerQuality) {}
}
