package ca.northline.console.application;

import ca.northline.merchants.api.BusinessNames;
import ca.northline.payments.api.AgentCases;
import ca.northline.payments.api.AgentCases.CaseRow;
import ca.northline.payments.api.AgentCases.EvidenceFile;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.MerchantScope;
import ca.northline.trust.api.QualityQuery;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link DisputeDesk} composed from payments, merchants, region and trust (S-37: their {@code api} only). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DisputeDeskService implements DisputeDesk {

    static final int LIMIT = 200;
    static final Duration WEEK = Duration.ofDays(7);

    private final AgentCases cases;
    private final BusinessNames names;
    private final MerchantPlaces places;
    private final QualityQuery quality;
    private final Clock clock;

    @Override
    public Queue queue(MerchantScope scope) {
        var weekAgo = clock.instant().minus(WEEK);
        var businesses = new Businesses();
        return new Queue(
                cases.summary(scope, weekAgo),
                cases.queue(scope, weekAgo, LIMIT).stream().map(businesses::item).toList());
    }

    @Override
    public Optional<Detail> detail(String kind, String caseId) {
        var businesses = new Businesses();
        return cases.detail(kind, caseId).map(d -> new Detail(
                businesses.item(d.row()),
                d,
                quality.latest(d.row().merchantId()).map(QualityQuery.QualityScore::score).orElse(null)));
    }

    @Override
    @Transactional
    public Item decide(AgentCases.Decide command) {
        return new Businesses().item(cases.decide(command));
    }

    @Override
    @Transactional
    public Item cosign(AgentCases.Cosign command) {
        return new Businesses().item(cases.cosign(command));
    }

    @Override
    public Optional<EvidenceFile> evidence(String disputeId, String evidenceId) {
        return cases.evidence(disputeId, evidenceId);
    }

    /** Names and provinces, read once per business per request. */
    private final class Businesses {
        private final Map<String, String> nameById = new HashMap<>();
        private final Map<String, @Nullable String> provinceById = new HashMap<>();

        Item item(CaseRow row) {
            var id = row.merchantId();
            var name = nameById.computeIfAbsent(id, m -> names.displayName(m).orElse(m));
            if (!provinceById.containsKey(id)) {
                var place = places.of(id);
                provinceById.put(id, place.ownProvince() ? place.province() : null);
            }
            return new Item(row, name, provinceById.get(id));
        }
    }
}
