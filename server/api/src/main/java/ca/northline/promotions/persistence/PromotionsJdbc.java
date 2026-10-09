package ca.northline.promotions.persistence;

import ca.northline.promotions.api.Promotions.Line;
import ca.northline.promotions.application.PromotionStore;
import ca.northline.promotions.domain.PromoCode;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PromotionStore} over the {@code promotions} schema (V361). */
@Repository
@RequiredArgsConstructor
class PromotionsJdbc implements PromotionStore {

    /** A use that counts: redeemed, or held by a checkout that hasn't lapsed. */
    private static final String COUNTS =
            "(r.state = 'redeemed' or (r.state = 'reserved' and r.reserved_until > :now))"
                    + " and not (r.kind = :exceptKind and r.ref_id = :exceptRef)";

    private final JdbcClient jdbc;

    @Override
    public Optional<PromoCode> byCode(String code) {
        return jdbc.sql("select * from promotions.codes where code = :c")
                .param("c", code)
                .query((rs, _) -> code(rs))
                .optional();
    }

    @Override
    public Optional<PromoCode> lockByCode(String code) {
        return jdbc.sql("select * from promotions.codes where code = :c for update")
                .param("c", code)
                .query((rs, _) -> code(rs))
                .optional();
    }

    @Override
    public Optional<PromoCode> byId(String id) {
        return jdbc.sql("select * from promotions.codes where id = :id")
                .param("id", id)
                .query((rs, _) -> code(rs))
                .optional();
    }

    @Override
    public List<PromoCode> all(int limit) {
        return jdbc.sql("select * from promotions.codes order by created_at desc limit :limit")
                .param("limit", limit)
                .query((rs, _) -> code(rs))
                .list();
    }

    @Override
    public boolean insert(PromoCode c, String staffId, Instant at) {
        return jdbc.sql("""
                        insert into promotions.codes (id, code, description, kind, percent, amount_cents,
                               max_discount_cents, min_spend_cents, starts_at, ends_at, per_customer_limit, total_limit,
                               funded_by, merchant_id, applies_to, active, created_by, created_at, updated_at)
                        values (:id, :code, :description, :kind, :percent, :amount, :max, :min, :starts, :ends,
                                :perCustomer, :total, :funder, :merchant, :appliesTo, :active, :by, :at, :at)
                        on conflict (code) do nothing
                        """)
                        .param("id", c.id())
                        .param("code", c.code())
                        .param("description", c.description())
                        .param("kind", c.kind())
                        .param("percent", c.percent())
                        .param("amount", c.amountCents())
                        .param("max", c.maxDiscountCents())
                        .param("min", c.minSpendCents())
                        .param("starts", JdbcTimes.ts(c.startsAt()))
                        .param("ends", JdbcTimes.ts(c.endsAt()))
                        .param("perCustomer", c.perCustomerLimit())
                        .param("total", c.totalLimit())
                        .param("funder", c.fundedBy())
                        .param("merchant", c.merchantId())
                        .param("appliesTo", c.appliesTo().stream().sorted().toArray(String[]::new))
                        .param("active", c.active())
                        .param("by", staffId)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public boolean setActive(String id, boolean active, Instant at) {
        return jdbc.sql("""
                        update promotions.codes set active = :active, updated_at = :at
                         where id = :id and active <> :active""")
                        .param("id", id)
                        .param("active", active)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public int uses(String codeId, @Nullable String customerId, Instant now, String exceptKind, String exceptRef) {
        return jdbc.sql("select count(*) from promotions.redemptions r where r.code_id = :code"
                        + " and (cast(:customer as text) is null or r.customer_id = :customer) and " + COUNTS)
                .param("code", codeId)
                .param("customer", customerId)
                .param("now", JdbcTimes.ts(now))
                .param("exceptKind", exceptKind)
                .param("exceptRef", exceptRef)
                .query(Integer.class)
                .single();
    }

    @Override
    public Usage usage(String codeId) {
        return jdbc.sql("""
                        select count(*)::int as n, coalesce(sum(discount_cents), 0) as d from promotions.redemptions
                         where code_id = :code and state = 'redeemed'""")
                .param("code", codeId)
                .query((rs, _) -> new Usage(rs.getInt("n"), rs.getLong("d")))
                .single();
    }

    @Override
    public long pointsHeld(String customerId, Instant now, String exceptKind, String exceptRef) {
        return jdbc.sql("""
                        select coalesce(sum(r.points), 0) from promotions.redemptions r
                         where r.customer_id = :customer and r.state = 'reserved' and r.reserved_until > :now
                           and not (r.kind = :exceptKind and r.ref_id = :exceptRef)""")
                .param("customer", customerId)
                .param("now", JdbcTimes.ts(now))
                .param("exceptKind", exceptKind)
                .param("exceptRef", exceptRef)
                .query(Long.class)
                .single();
    }

    @Override
    public void lockCustomer(String customerId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('promotions.points:' || :customer))")
                .param("customer", customerId)
                .query((rs, _) -> 1)
                .list();
    }

    @Override
    public Optional<Redemption> redemption(String kind, String refId) {
        return jdbc.sql("""
                        select r.*, c.code from promotions.redemptions r
                          left join promotions.codes c on c.id = r.code_id
                         where r.kind = :kind and r.ref_id = :ref""")
                .param("kind", kind)
                .param("ref", refId)
                .query((rs, _) -> redemption(rs))
                .optional();
    }

    @Override
    public Optional<Redemption> byEscrow(String escrowRefType, String escrowRefId) {
        return jdbc.sql("""
                        select r.*, c.code from promotions.redemptions r
                          join promotions.redemption_lines l on l.redemption_id = r.id
                          left join promotions.codes c on c.id = r.code_id
                         where l.escrow_ref_type = :type and l.escrow_ref_id = :ref""")
                .param("type", escrowRefType)
                .param("ref", escrowRefId)
                .query((rs, _) -> redemption(rs))
                .optional();
    }

    @Override
    public void save(Redemption r, Instant at) {
        jdbc.sql("""
                        insert into promotions.redemptions (id, customer_id, kind, ref_id, code_id, discount_cents, points,
                               points_cents, state, reserved_until, created_at)
                        values (:id, :customer, :kind, :ref, :code, :discount, :points, :pointsCents, :state, :until, :at)
                        """)
                .param("id", r.id())
                .param("customer", r.customerId())
                .param("kind", r.kind())
                .param("ref", r.refId())
                .param("code", r.codeId())
                .param("discount", r.discountCents())
                .param("points", r.points())
                .param("pointsCents", r.pointsCents())
                .param("state", r.state())
                .param("until", JdbcTimes.ts(r.reservedUntil()))
                .param("at", JdbcTimes.ts(at))
                .update();
        for (var l : r.lines()) {
            jdbc.sql("""
                            insert into promotions.redemption_lines (redemption_id, escrow_ref_type, escrow_ref_id,
                                   merchant_id, amount_cents, discount_cents, points_cents)
                            values (:r, :type, :ref, :merchant, :amount, :discount, :points)
                            """)
                    .param("r", r.id())
                    .param("type", l.escrowRefType())
                    .param("ref", l.escrowRefId())
                    .param("merchant", l.merchantId())
                    .param("amount", l.amountCents())
                    .param("discount", l.discountCents())
                    .param("points", l.pointsCents())
                    .update();
        }
    }

    @Override
    public void delete(String kind, String refId) {
        var ids = jdbc.sql("""
                        select id from promotions.redemptions
                         where kind = :kind and ref_id = :ref and state <> 'redeemed'""")
                .param("kind", kind)
                .param("ref", refId)
                .query(String.class)
                .list();
        for (var id : ids) {
            jdbc.sql("delete from promotions.redemption_lines where redemption_id = :id")
                    .param("id", id)
                    .update();
            jdbc.sql("delete from promotions.redemptions where id = :id")
                    .param("id", id)
                    .update();
        }
    }

    @Override
    public boolean redeemed(String id, Instant at) {
        return jdbc.sql("""
                        update promotions.redemptions set state = 'redeemed', redeemed_at = :at
                         where id = :id and state = 'reserved'""")
                        .param("id", id)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public boolean released(String id, Instant at) {
        return jdbc.sql("""
                        update promotions.redemptions set state = 'released', released_at = :at
                         where id = :id and state <> 'released'""")
                        .param("id", id)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public long pointsReturned(String escrowRefType, String escrowRefId, long cents) {
        return jdbc.sql("""
                        update promotions.redemption_lines
                           set points_returned_cents = least(points_cents, points_returned_cents + :cents)
                         where escrow_ref_type = :type and escrow_ref_id = :ref
                        returning points_returned_cents""")
                .param("type", escrowRefType)
                .param("ref", escrowRefId)
                .param("cents", cents)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private Redemption redemption(ResultSet rs) throws SQLException {
        var id = rs.getString("id");
        var lines = jdbc.sql("""
                        select * from promotions.redemption_lines l
                         where l.redemption_id = :id order by l.escrow_ref_id""")
                .param("id", id)
                .query((l, _) -> new Line(
                        l.getString("escrow_ref_type"),
                        l.getString("escrow_ref_id"),
                        l.getString("merchant_id"),
                        l.getLong("amount_cents"),
                        l.getLong("discount_cents"),
                        null,
                        l.getLong("points_cents")))
                .list();
        var funder = rs.getString("code_id") == null
                ? null
                : jdbc.sql("select funded_by from promotions.codes where id = :id")
                        .param("id", rs.getString("code_id"))
                        .query(String.class)
                        .single();
        return new Redemption(
                id,
                rs.getString("customer_id"),
                rs.getString("kind"),
                rs.getString("ref_id"),
                rs.getString("code_id"),
                rs.getString("code"),
                funder,
                rs.getLong("discount_cents"),
                rs.getLong("points"),
                rs.getLong("points_cents"),
                rs.getString("state"),
                JdbcTimes.requiredInstant(rs, "reserved_until"),
                lines.stream()
                        .map(l -> l.discountCents() > 0
                                ? new Line(
                                        l.escrowRefType(),
                                        l.escrowRefId(),
                                        l.merchantId(),
                                        l.amountCents(),
                                        l.discountCents(),
                                        funder,
                                        l.pointsCents())
                                : l)
                        .toList());
    }

    private static PromoCode code(ResultSet rs) throws SQLException {
        var applies = rs.getArray("applies_to");
        return new PromoCode(
                rs.getString("id"),
                rs.getString("code"),
                rs.getString("description"),
                rs.getString("kind"),
                (Integer) rs.getObject("percent"),
                (Long) rs.getObject("amount_cents"),
                (Long) rs.getObject("max_discount_cents"),
                rs.getLong("min_spend_cents"),
                JdbcTimes.requiredInstant(rs, "starts_at"),
                JdbcTimes.requiredInstant(rs, "ends_at"),
                rs.getInt("per_customer_limit"),
                (Integer) rs.getObject("total_limit"),
                rs.getString("funded_by"),
                rs.getString("merchant_id"),
                new HashSet<>(Arrays.stream((Object[]) applies.getArray())
                        .map(String::valueOf)
                        .toList()),
                rs.getBoolean("active"));
    }
}
