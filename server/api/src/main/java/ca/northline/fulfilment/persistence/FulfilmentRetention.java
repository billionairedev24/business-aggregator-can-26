package ca.northline.fulfilment.persistence;

import ca.northline.fulfilment.application.ProofStorage;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.RetentionContributor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-107, fulfilment: {@code fulfilment.delivery_proofs} — "Messages and dispute evidence: 2 years after the transaction,
 * or until a dispute is closed plus 1 year, whichever is later". Two years after a drop-off its proof photo or signature
 * goes from object storage ({@link ProofStorage}); the stop keeps how it was proven ({@code proof_kind}). A dispute
 * about the order keeps it as the privacy module decides (a year after the decision, longer where the customer's
 * province's law asks). A delivery under way is a legal hold for every module ({@link Hold#ACTIVE_DELIVERY}).
 */
@Component
@RequiredArgsConstructor
class FulfilmentRetention implements RetentionContributor {

    static final String PROOFS = "fulfilment.delivery_proofs";

    private static final String DUE = """
            from fulfilment.stops s
             where s.kind = 'dropoff' and s.proof_media_id is not null and s.state = 'done' and s.done_at < :cutoff
               and not ('order:' || coalesce(s.order_id, '') = any(:held))
            """;

    private final JdbcClient jdbc;
    private final ProofStorage proofs;

    @Override
    public String module() {
        return "fulfilment";
    }

    @Override
    public Set<String> categories() {
        return Set.of(PROOFS);
    }

    @Override
    public List<HeldRef> holds(Instant now) {
        return jdbc.sql("select order_id from fulfilment.deliveries where state in " + FulfilmentPersonalData.ACTIVE)
                .query((rs, _) -> rs.getString(1))
                .list()
                .stream()
                .map(id -> HeldRef.open("order", id, Hold.ACTIVE_DELIVERY))
                .toList();
    }

    @Override
    public long expired(Run run) {
        return jdbc.sql("select count(*) " + DUE)
                .params(Map.of("cutoff", run.before(), "held", run.heldKeys()))
                .query(Long.class)
                .single();
    }

    @Override
    public long purge(Run run) {
        var due = jdbc.sql("select s.id, s.proof_media_id " + DUE + " order by s.done_at limit :batch")
                .params(Map.of("cutoff", run.before(), "held", run.heldKeys(), "batch", run.batch()))
                .query((rs, _) -> Map.entry(rs.getString("id"), rs.getString("proof_media_id")))
                .list();
        for (var stop : due) {
            proofs.delete(stop.getValue());
        }
        if (!due.isEmpty()) {
            jdbc.sql("update fulfilment.stops set proof_media_id = null where id = any(:ids)")
                    .param("ids", due.stream().map(Map.Entry::getKey).toArray(String[]::new))
                    .update();
        }
        return due.size();
    }
}
