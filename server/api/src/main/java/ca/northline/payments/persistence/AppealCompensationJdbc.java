package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.application.AppealCompensationStore;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AppealCompensationStore} over {@code payments.appeal_compensations} and {@code agent_decisions}. */
@Repository
@RequiredArgsConstructor
class AppealCompensationJdbc implements AppealCompensationStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<Long> cents(String appealId) {
        return jdbc.sql("select cents from payments.appeal_compensations where appeal_id = :id")
                .param("id", appealId)
                .query(Long.class)
                .optional();
    }

    @Override
    public boolean insert(String appealId, String disputeId, String merchantId, long cents, String by, Instant at) {
        return jdbc.sql("""
                        insert into payments.appeal_compensations
                          (appeal_id, dispute_id, merchant_id, cents, created_by, created_at)
                        values (:appeal, :dispute, :merchant, :cents, :by, :at) on conflict do nothing
                        """)
                        .param("appeal", appealId)
                        .param("dispute", disputeId)
                        .param("merchant", merchantId)
                        .param("cents", cents)
                        .param("by", by)
                        .param("at", ts(at))
                        .update()
                > 0;
    }

    @Override
    public void transfer(String appealId, String stripeTransfer) {
        jdbc.sql("update payments.appeal_compensations set stripe_transfer = :tr where appeal_id = :id")
                .param("tr", stripeTransfer)
                .param("id", appealId)
                .update();
    }

    @Override
    public boolean agentDecided(String disputeId) {
        return jdbc.sql("""
                        select exists (select 1 from payments.agent_decisions
                                        where case_kind = 'dispute' and case_id = :id and state = 'applied')
                        """).param("id", disputeId).query(Boolean.class).single();
    }
}
