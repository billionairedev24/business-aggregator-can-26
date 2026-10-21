package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.instant;
import static ca.northline.catalogue.persistence.Sql.intOrNull;
import static ca.northline.catalogue.persistence.Sql.requiredInstant;
import static ca.northline.catalogue.persistence.Sql.ts;

import ca.northline.catalogue.application.CommerceStockPushStore;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code catalogue.commerce_stock_pushes} and the order-line → platform-variant lookups (S-158). */
@Repository
@RequiredArgsConstructor
class CommerceStockPushJdbc implements CommerceStockPushStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<String> skuOf(String merchantId, String offerId, @Nullable String variantId) {
        if (variantId == null) {
            return jdbc.sql("select sku from catalogue.offers where id = :o and merchant_id = :m and sku is not null")
                    .param("o", offerId)
                    .param("m", merchantId)
                    .query(String.class)
                    .optional();
        }
        return jdbc.sql("""
                        select v.sku from catalogue.variants v join catalogue.offers o on o.id = v.offer_id
                        where v.id = :v and v.offer_id = :o and o.merchant_id = :m and v.sku is not null""")
                .param("v", variantId)
                .param("o", offerId)
                .param("m", merchantId)
                .query(String.class)
                .optional();
    }

    @Override
    public List<Target> targets(String merchantId, String offerId, String sku) {
        return jdbc.sql("""
                        select i.id, v.provider, v.external_id, v.stock_ref
                        from catalogue.commerce_variants v
                        join catalogue.commerce_products p on p.merchant_id = v.merchant_id and p.provider = v.provider
                          and p.external_id = v.product_external_id
                        join catalogue.integrations i on i.merchant_id = v.merchant_id and i.provider = v.provider
                        where v.merchant_id = :m and v.offer_id = :o and v.sku = :sku and p.removed_at is null
                          and i.status = 'connected' and i.id is not null
                        order by v.provider, v.external_id""")
                .param("m", merchantId)
                .param("o", offerId)
                .param("sku", sku)
                .query((rs, _) -> new Target(
                        Objects.requireNonNull(rs.getString(1)),
                        CodedEnum.fromCode(CommerceProvider.class, rs.getString(2)),
                        Objects.requireNonNull(rs.getString(3)),
                        rs.getString(4)))
                .list();
    }

    @Override
    public boolean insert(Push p) {
        return jdbc.sql("""
                                insert into catalogue.commerce_stock_pushes (id, merchant_id, provider, integration_id,
                                  offer_id, sku, external_variant_id, stock_ref, delta, reason, source_ref,
                                  idempotency_key, state, conflict, attempts, next_attempt_at, last_error, created_at,
                                  done_at)
                                values (:id, :m, :p, :i, :o, :sku, :v, :ref, :delta, :reason, :src, :key, :state,
                                  :conflict, 0, :next, :error, :at, null)
                                on conflict (idempotency_key) do nothing""")
                        .param("id", p.id())
                        .param("m", p.merchantId())
                        .param("p", p.provider().code())
                        .param("i", p.integrationId())
                        .param("o", p.offerId())
                        .param("sku", p.sku())
                        .param("v", p.externalVariantId())
                        .param("ref", p.stockRef())
                        .param("delta", p.delta())
                        .param("reason", p.reason())
                        .param("src", p.sourceRef())
                        .param("key", p.idempotencyKey())
                        .param("state", p.state().name().toLowerCase(Locale.ROOT))
                        .param("conflict", p.conflict())
                        .param("next", ts(p.nextAttemptAt()))
                        .param("error", p.lastError())
                        .param("at", ts(p.createdAt()))
                        .update()
                == 1;
    }

    @Override
    public List<Push> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc
                .sql("""
                        update catalogue.commerce_stock_pushes set next_attempt_at = :lease
                        where id in (select id from catalogue.commerce_stock_pushes
                                     where state = 'pending' and next_attempt_at <= :now
                                     order by next_attempt_at, created_at limit :limit for update skip locked)
                        returning *""")
                .param("now", ts(now))
                .param("lease", ts(leaseUntil))
                .param("limit", limit)
                .query((rs, _) -> map(rs))
                .list()
                .stream()
                .sorted(java.util.Comparator.comparing(Push::createdAt))
                .toList();
    }

    @Override
    public void complete(String id, @Nullable String locationId, @Nullable Integer after, Instant at) {
        jdbc.sql("""
                        update catalogue.commerce_stock_pushes set state = 'done', location_id = :loc,
                          platform_after = :after, done_at = :at, last_error = null, attempts = attempts + 1
                        where id = :id""")
                .param("id", id)
                .param("loc", locationId)
                .param("after", after)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void conflict(
            String id,
            String conflict,
            String detail,
            @Nullable String locationId,
            @Nullable Integer after,
            @Nullable Instant appliedAt,
            Instant at) {
        jdbc.sql("""
                        update catalogue.commerce_stock_pushes set state = 'conflict', conflict = :c, last_error = :e,
                          location_id = coalesce(:loc, location_id), platform_after = :after, done_at = :applied,
                          attempts = attempts + 1, next_attempt_at = :at
                        where id = :id""")
                .param("id", id)
                .param("c", conflict)
                .param("e", truncate(detail))
                .param("loc", locationId)
                .param("after", after)
                .param("applied", ts(appliedAt))
                .param("at", ts(at))
                .update();
    }

    @Override
    public void retryLater(String id, int attempts, Instant next, String error) {
        jdbc.sql("""
                        update catalogue.commerce_stock_pushes set attempts = :n, next_attempt_at = :next,
                          last_error = :e where id = :id""")
                .param("id", id)
                .param("n", attempts)
                .param("next", ts(next))
                .param("e", truncate(error))
                .update();
    }

    @Override
    public void fail(String id, int attempts, String error, Instant at) {
        jdbc.sql("""
                        update catalogue.commerce_stock_pushes set state = 'failed', attempts = :n, last_error = :e,
                          next_attempt_at = :at where id = :id""")
                .param("id", id)
                .param("n", attempts)
                .param("e", truncate(error))
                .param("at", ts(at))
                .update();
    }

    @Override
    public Optional<Push> find(String merchantId, String id) {
        return jdbc.sql("select * from catalogue.commerce_stock_pushes where id = :id and merchant_id = :m")
                .param("id", id)
                .param("m", merchantId)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public void requeue(String id, String by, Instant at) {
        jdbc.sql("""
                        update catalogue.commerce_stock_pushes set state = 'pending', conflict = null, attempts = 0,
                          next_attempt_at = :at, resolved_by = :by, resolved_at = :at where id = :id""").param("id", id).param("by", by).param("at", ts(at)).update();
    }

    @Override
    public void dismiss(String id, String by, Instant at) {
        jdbc.sql("""
                        update catalogue.commerce_stock_pushes set state = 'dismissed', resolved_by = :by,
                          resolved_at = :at where id = :id""").param("id", id).param("by", by).param("at", ts(at)).update();
    }

    @Override
    public int requeueConflicts(String integrationId, List<String> conflicts, Instant at) {
        return jdbc.sql("""
                        update catalogue.commerce_stock_pushes set state = 'pending', conflict = null, attempts = 0,
                          next_attempt_at = :at
                        where integration_id = :i and state = 'conflict' and done_at is null
                          and conflict in (:codes)""")
                .param("i", integrationId)
                .param("codes", conflicts)
                .param("at", ts(at))
                .update();
    }

    @Override
    public List<Push> attention(String merchantId, CommerceProvider provider, int limit) {
        return jdbc.sql("""
                        select * from catalogue.commerce_stock_pushes
                        where merchant_id = :m and provider = :p and state in ('conflict', 'failed')
                        order by created_at desc limit :limit""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("limit", limit)
                .query((rs, _) -> map(rs))
                .list();
    }

    @Override
    public Counts counts(String merchantId, CommerceProvider provider, Instant doneSince) {
        return jdbc.sql("""
                        select count(*) filter (where state = 'pending'),
                               count(*) filter (where state = 'done' and done_at >= :since),
                               count(*) filter (where state in ('conflict', 'failed'))
                        from catalogue.commerce_stock_pushes where merchant_id = :m and provider = :p""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("since", ts(doneSince))
                .query((rs, _) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                .single();
    }

    @Override
    public Map<String, Integer> pendingBySku(String merchantId, CommerceProvider provider) {
        var out = new HashMap<String, Integer>();
        jdbc.sql("""
                        select sku, sum(delta) from catalogue.commerce_stock_pushes
                        where merchant_id = :m and provider = :p and state = 'pending' group by sku""").param("m", merchantId).param("p", provider.code()).query(rs -> {
            out.put(Objects.requireNonNull(rs.getString(1)), rs.getInt(2));
        });
        return out;
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static Push map(ResultSet rs) throws SQLException {
        return new Push(
                Objects.requireNonNull(rs.getString("id")),
                Objects.requireNonNull(rs.getString("merchant_id")),
                CodedEnum.fromCode(CommerceProvider.class, rs.getString("provider")),
                Objects.requireNonNull(rs.getString("integration_id")),
                Objects.requireNonNull(rs.getString("offer_id")),
                Objects.requireNonNull(rs.getString("sku")),
                Objects.requireNonNull(rs.getString("external_variant_id")),
                rs.getString("stock_ref"),
                rs.getInt("delta"),
                Objects.requireNonNull(rs.getString("reason")),
                Objects.requireNonNull(rs.getString("source_ref")),
                Objects.requireNonNull(rs.getString("idempotency_key")),
                State.valueOf(Objects.requireNonNull(rs.getString("state")).toUpperCase(Locale.ROOT)),
                rs.getString("conflict"),
                rs.getInt("attempts"),
                requiredInstant(rs, "next_attempt_at"),
                rs.getString("last_error"),
                rs.getString("location_id"),
                intOrNull(rs, "platform_after"),
                requiredInstant(rs, "created_at"),
                instant(rs, "done_at"));
    }
}
