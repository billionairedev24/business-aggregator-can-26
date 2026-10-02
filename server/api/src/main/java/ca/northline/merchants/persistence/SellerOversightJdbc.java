package ca.northline.merchants.persistence;

import ca.northline.merchants.api.SellerDirectory;
import ca.northline.merchants.application.OversightStore;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.MerchantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link SellerDirectory} and {@link OversightStore} over {@code merchants.merchants}, {@code merchant_categories},
 * {@code verifications} and {@code oversight_actions} (S-82).
 */
@Repository
@RequiredArgsConstructor
class SellerOversightJdbc implements SellerDirectory, OversightStore {

    /** Checks that need someone: not verified yet, refused, expired, or expiring within 30 days. */
    private static final String NEEDS_ATTENTION = """
            (v.status in ('todo', 'submitted', 'expired', 'rejected')
             or (v.status = 'verified' and v.expires_at is not null and v.expires_at < now() + interval '30 days'))""";

    private static final String SELLER = """
            select m.id, m.display_name, m.type, m.tier, m.status, m.province, m.city, m.created_at, m.approved_at,
                   m.stripe_account_id, case when m.search_hidden_at is not null then m.search_hidden_cause end as search_hidden,
                   (select array_agg(c.category_id order by c.category_id) from merchants.merchant_categories c
                     where c.merchant_id = m.id and c.status is distinct from 'rejected' and c.category_id is not null)
                     as categories
              from merchants.merchants m""";

    private final JdbcClient jdbc;
    private final MerchantJsonColumns json;

    // ── SellerDirectory ───────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<Seller> sellers(MerchantScope scope, @Nullable String q, int limit) {
        var rows = jdbc.sql(SELLER + """
                         where m.status <> 'applicant'
                           and (:everyone or m.id = any(:merchants))
                           and (:q = '' or m.display_name ilike '%' || :q || '%' or m.legal_name ilike '%' || :q || '%')
                         order by lower(m.display_name), m.id
                         limit :limit
                        """)
                .param("everyone", scope.everyone())
                .param("merchants", scope.ids())
                .param("q", q == null ? "" : q.strip().replace("%", "").replace("_", ""))
                .param("limit", limit)
                .query((rs, _) -> seller(rs, List.of()))
                .list();
        if (rows.isEmpty()) {
            return rows;
        }
        var attention = new HashMap<String, List<Check>>();
        jdbc.sql("select v.* from merchants.verifications v where v.merchant_id = any(:ids) and " + NEEDS_ATTENTION
                        + " order by v.expires_at nulls last, v.id")
                .param("ids", rows.stream().map(Seller::id).toArray(String[]::new))
                .query((rs, _) -> Map.entry(rs.getString("merchant_id"), check(rs)))
                .list()
                .forEach(e -> attention
                        .computeIfAbsent(e.getKey(), _ -> new ArrayList<>())
                        .add(e.getValue()));
        return rows.stream()
                .map(s -> withAttention(s, attention.getOrDefault(s.id(), List.of())))
                .toList();
    }

    @Override
    public Optional<SellerFile> seller(String merchantId) {
        return jdbc.sql(SELLER + " where m.id = :id")
                .param("id", merchantId)
                .query((rs, _) ->
                        Map.entry(seller(rs, List.of()), Optional.ofNullable(rs.getString("stripe_account_id"))))
                .optional()
                .map(e -> {
                    var checks = jdbc.sql("""
                                    select v.* from merchants.verifications v where v.merchant_id = :id
                                     order by v.check_type, v.expires_at nulls last, v.id""")
                            .param("id", merchantId)
                            .query((rs, _) -> check(rs))
                            .list();
                    var due = jdbc.sql("select v.* from merchants.verifications v where v.merchant_id = :id and "
                                    + NEEDS_ATTENTION + " order by v.expires_at nulls last, v.id")
                            .param("id", merchantId)
                            .query((rs, _) -> check(rs))
                            .list();
                    var trail = jdbc.sql("""
                                    select * from merchants.oversight_actions where merchant_id = :id
                                     order by at desc, id desc""")
                            .param("id", merchantId)
                            .query((rs, _) -> oversight(rs))
                            .list();
                    return new SellerFile(
                            withAttention(e.getKey(), due), e.getValue().orElse(null), checks, trail);
                });
    }

    @Override
    public Optional<Oversight> action(String actionId) {
        return jdbc.sql("select * from merchants.oversight_actions where id = :id")
                .param("id", actionId)
                .query((rs, _) -> oversight(rs))
                .optional();
    }

    @Override
    public Map<String, String> tiers() {
        var out = new HashMap<String, String>();
        jdbc.sql("select id, tier from merchants.merchants where status = 'active' and tier is not null")
                .query((rs, _) -> out.put(rs.getString("id"), rs.getString("tier")))
                .list();
        return Map.copyOf(out);
    }

    // ── OversightStore ────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<State> lock(String merchantId) {
        return jdbc.sql("select id, status, tier from merchants.merchants where id = :id for update")
                .param("id", merchantId)
                .query((rs, _) -> new State(rs.getString("id"), rs.getString("status"), rs.getString("tier")))
                .optional();
    }

    @Override
    public void status(String merchantId, String status, Instant at) {
        jdbc.sql("update merchants.merchants set status = :s, updated_at = :at where id = :id")
                .param("s", status)
                .param("at", JdbcTimes.ts(at))
                .param("id", merchantId)
                .update();
    }

    @Override
    public void tier(String merchantId, String tier, Instant at) {
        jdbc.sql("update merchants.merchants set tier = :t, updated_at = :at where id = :id")
                .param("t", tier)
                .param("at", JdbcTimes.ts(at))
                .param("id", merchantId)
                .update();
    }

    @Override
    public Optional<Check> check(String merchantId, String verificationId) {
        return jdbc.sql("select v.* from merchants.verifications v where v.id = :id and v.merchant_id = :m")
                .param("id", verificationId)
                .param("m", merchantId)
                .query((rs, _) -> check(rs))
                .optional();
    }

    @Override
    public void expire(String verificationId, Instant at) {
        jdbc.sql("update merchants.verifications set status = 'expired', expires_at = :at where id = :id")
                .param("at", JdbcTimes.ts(at))
                .param("id", verificationId)
                .update();
    }

    @Override
    public Optional<String> searchHidden(String merchantId) {
        return jdbc.sql(
                        "select search_hidden_cause from merchants.merchants where id = :id and search_hidden_at is not null")
                .param("id", merchantId)
                .query(String.class)
                .optional();
    }

    @Override
    public void searchHidden(String merchantId, @Nullable String cause, Instant at) {
        jdbc.sql("""
                        update merchants.merchants
                           set search_hidden_at = case when cast(:cause as text) is null then null else cast(:at as timestamptz) end,
                               search_hidden_cause = :cause, updated_at = :at
                         where id = :id""")
                .param("cause", cause, java.sql.Types.VARCHAR)
                .param("at", JdbcTimes.ts(at))
                .param("id", merchantId)
                .update();
    }

    @Override
    public List<String> hiddenFromSearch(String cause) {
        return jdbc.sql("""
                        select id from merchants.merchants
                         where search_hidden_at is not null and search_hidden_cause = :cause order by id""")
                .param("cause", cause)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    @Override
    public void insert(Oversight action, String merchantId) {
        jdbc.sql("""
                        insert into merchants.oversight_actions (id, merchant_id, action, reason, detail, actor_id,
                                                                 actor_role, at)
                        values (:id, :m, :a, :r, cast(:d as jsonb), :actor, :role, :at)""")
                .param("id", action.id())
                .param("m", merchantId)
                .param("a", action.action())
                .param("r", action.reason())
                .param("d", json.write(action.detail()))
                .param("actor", action.actorId())
                .param("role", action.actorRole())
                .param("at", JdbcTimes.ts(action.at()))
                .update();
    }

    // ── rows ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static Seller seller(ResultSet rs, List<Check> attention) throws SQLException {
        var categories = rs.getArray("categories");
        return new Seller(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getString("type"),
                rs.getString("tier"),
                rs.getString("status"),
                rs.getString("province"),
                rs.getString("city"),
                categories == null ? List.of() : Arrays.asList((String[]) categories.getArray()),
                JdbcTimes.requiredInstant(rs, "created_at"),
                JdbcTimes.instant(rs, "approved_at"),
                attention,
                rs.getString("search_hidden"));
    }

    private static Seller withAttention(Seller s, List<Check> attention) {
        return new Seller(
                s.id(),
                s.name(),
                s.type(),
                s.tier(),
                s.status(),
                s.province(),
                s.city(),
                s.categoryIds(),
                s.createdAt(),
                s.approvedAt(),
                attention,
                s.searchHidden());
    }

    private static Check check(ResultSet rs) throws SQLException {
        return new Check(
                rs.getString("id"),
                rs.getString("check_type"),
                rs.getString("registry"),
                rs.getString("reference"),
                rs.getString("status"),
                JdbcTimes.instant(rs, "expires_at"));
    }

    private Oversight oversight(ResultSet rs) throws SQLException {
        var detail = new LinkedHashMap<String, String>();
        json.map(rs.getString("detail")).forEach((k, v) -> detail.put(k, String.valueOf(v)));
        return new Oversight(
                rs.getString("id"),
                rs.getString("action"),
                rs.getString("reason"),
                detail,
                rs.getString("actor_id"),
                rs.getString("actor_role"),
                JdbcTimes.requiredInstant(rs, "at"));
    }
}
