package ca.northline.trust.persistence;

import ca.northline.shared.Ids;
import ca.northline.trust.application.RewardStore;
import ca.northline.trust.domain.MerchantReward;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link RewardStore} over {@code trust.merchant_rewards} (S-75): one row per business, upserted. */
@Repository
@RequiredArgsConstructor
class RewardJdbc implements RewardStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<MerchantReward> find(String merchantId) {
        return jdbc.sql("""
                        select merchant_id, active, coalesce(multiplier, 2) as multiplier, label, ends_on, updated_at
                          from trust.merchant_rewards where merchant_id = :m and ends_on is not null
                        """)
                .param("m", merchantId)
                .query((rs, _) -> new MerchantReward(
                        rs.getString("merchant_id"),
                        rs.getBoolean("active"),
                        rs.getInt("multiplier"),
                        rs.getString("label"),
                        rs.getObject("ends_on", LocalDate.class),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public void save(MerchantReward r, String actorId) {
        jdbc.sql("""
                        insert into trust.merchant_rewards (id, merchant_id, scope, multiplier, active, label, ends_on,
                                                            updated_at, updated_by)
                        values (:id, :m, '"all"'::jsonb, :multiplier, :active, :label, :endsOn, :at, :by)
                        on conflict (merchant_id) do update set multiplier = excluded.multiplier, active = excluded.active,
                          label = excluded.label, ends_on = excluded.ends_on, updated_at = excluded.updated_at,
                          updated_by = excluded.updated_by
                        """)
                .param("id", Ids.next())
                .param("m", r.merchantId())
                .param("multiplier", r.multiplier())
                .param("active", r.active())
                .param("label", r.label())
                .param("endsOn", r.endsOn())
                .param("at", r.updatedAt().atOffset(ZoneOffset.UTC))
                .param("by", actorId)
                .update();
    }
}
