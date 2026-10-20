package ca.northline.booking.persistence;

import ca.northline.booking.api.CancellationPolicies.Policy;
import ca.northline.booking.application.CancellationPolicyStore;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CancellationPolicyStore} over {@code booking.cancellation_policies} (V407). */
@Repository
@RequiredArgsConstructor
class CancellationPolicyJdbc implements CancellationPolicyStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<Policy> find(String merchantId) {
        return jdbc.sql("select window_hours, fee_bps from booking.cancellation_policies where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> new Policy(rs.getInt("window_hours"), rs.getInt("fee_bps")))
                .optional();
    }

    @Override
    public void save(String merchantId, Policy policy, String actorId, Instant at) {
        jdbc.sql("""
                        insert into booking.cancellation_policies (merchant_id, window_hours, fee_bps, updated_at, updated_by)
                        values (:m, :w, :f, :at, :by)
                        on conflict (merchant_id) do update
                           set window_hours = excluded.window_hours, fee_bps = excluded.fee_bps,
                               updated_at = excluded.updated_at, updated_by = excluded.updated_by
                        """)
                .param("m", merchantId)
                .param("w", policy.windowHours())
                .param("f", policy.feeBps())
                .param("at", JdbcTimes.ts(at))
                .param("by", actorId)
                .update();
    }

    @Override
    public void delete(String merchantId) {
        jdbc.sql("delete from booking.cancellation_policies where merchant_id = :m")
                .param("m", merchantId)
                .update();
    }
}
