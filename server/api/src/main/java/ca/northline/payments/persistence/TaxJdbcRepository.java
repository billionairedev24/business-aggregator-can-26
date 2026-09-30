package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.application.TaxRepository;
import ca.northline.payments.domain.CanadianTax;
import ca.northline.payments.domain.CanadianTax.Province;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code payments.tax_calculations}, {@code payments.tax_transactions} (the unique {@code reference} is the dedupe) and
 * the Studio's read model {@code payments.tax_jurisdiction_totals}.
 */
@Repository
@RequiredArgsConstructor
class TaxJdbcRepository implements TaxRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<CanadianTax.Line>> LINES = new TypeReference<>() {};
    private static final String CALCULATION_COLUMNS = """
            id, stripe_calculation, merchant_id, kind, province, amount_cents, tax_cents, breakdown, ref_type, ref_id,
            expires_at, created_at
            """;
    private static final String TRANSACTION_COLUMNS = """
            id, reference, kind, merchant_id, escrow_id, escrow_kind, original_reference, calculation_id, province,
            amount_cents, tax_cents, period, occurred_at, state, stripe_transaction, stripe_tax_cents, attempts
            """;

    private final JdbcClient jdbc;

    // ── calculations ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void insertCalculation(Calculation c) {
        jdbc.sql("""
                        insert into payments.tax_calculations (id, stripe_calculation, merchant_id, kind, province,
                               jurisdiction, amount_cents, tax_cents, breakdown, ref_type, ref_id, expires_at, created_at)
                        values (:id, :stripe, :merchant, :kind, :province, :jurisdiction, :amount, :tax,
                                cast(:breakdown as jsonb), :refType, :refId, :expires, :created)""")
                .param("id", c.id())
                .param("stripe", c.stripeCalculation())
                .param("merchant", c.merchantId())
                .param("kind", c.kind().code())
                .param("province", c.province().name())
                .param("jurisdiction", c.province().jurisdiction())
                .param("amount", c.amountCents())
                .param("tax", c.taxCents())
                .param("breakdown", JSON.writeValueAsString(c.lines()))
                .param("refType", c.refType())
                .param("refId", c.refId())
                .param("expires", ts(c.expiresAt()))
                .param("created", ts(c.createdAt()))
                .update();
    }

    @Override
    public Optional<Calculation> calculation(String id) {
        return jdbc.sql("select " + CALCULATION_COLUMNS + " from payments.tax_calculations where id = :id")
                .param("id", id)
                .query(TaxJdbcRepository::calculation)
                .optional();
    }

    @Override
    public Optional<Calculation> calculationFor(String refType, String refId) {
        return jdbc.sql("select " + CALCULATION_COLUMNS + """
                         from payments.tax_calculations where ref_type = :type and ref_id = :ref
                         order by created_at desc limit 1""")
                .param("type", refType)
                .param("ref", refId)
                .query(TaxJdbcRepository::calculation)
                .optional();
    }

    @Override
    public boolean useCalculation(String id, String refType, String refId) {
        return jdbc.sql("""
                        update payments.tax_calculations set ref_type = :type, ref_id = :ref
                         where id = :id and (ref_id is null or (ref_type = :type and ref_id = :ref))""")
                        .param("id", id)
                        .param("type", refType)
                        .param("ref", refId)
                        .update()
                > 0;
    }

    private static Calculation calculation(ResultSet rs, int row) throws SQLException {
        return new Calculation(
                rs.getString("id"),
                rs.getString("stripe_calculation"),
                rs.getString("merchant_id"),
                CodedEnum.fromCode(EscrowKind.class, rs.getString("kind")),
                Province.valueOf(rs.getString("province")),
                rs.getLong("amount_cents"),
                rs.getLong("tax_cents"),
                JSON.readValue(rs.getString("breakdown"), LINES),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                requiredInstant(rs, "expires_at"),
                requiredInstant(rs, "created_at"));
    }

    // ── transactions ──────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean insert(Transaction t) {
        return jdbc.sql("""
                        insert into payments.tax_transactions (id, reference, kind, merchant_id, escrow_id, escrow_kind,
                               original_reference, calculation_id, province, jurisdiction, amount_cents, tax_cents,
                               period, occurred_at, state, created_at)
                        values (:id, :reference, :kind, :merchant, :escrow, :escrowKind, :original, :calculation,
                                :province, :jurisdiction, :amount, :tax, :period, :occurred, 'pending', :occurred)
                        on conflict (reference) do nothing""")
                        .param("id", t.id())
                        .param("reference", t.reference())
                        .param("kind", t.kind().code())
                        .param("merchant", t.merchantId())
                        .param("escrow", t.escrowId())
                        .param("escrowKind", t.escrowKind().code())
                        .param("original", t.originalReference())
                        .param("calculation", t.calculationId())
                        .param("province", t.province().name())
                        .param("jurisdiction", t.jurisdiction())
                        .param("amount", t.amountCents())
                        .param("tax", t.taxCents())
                        .param("period", t.period())
                        .param("occurred", ts(t.occurredAt()))
                        .update()
                > 0;
    }

    @Override
    public Optional<Transaction> lock(String reference) {
        return jdbc.sql("select " + TRANSACTION_COLUMNS
                        + " from payments.tax_transactions where reference = :ref for update")
                .param("ref", reference)
                .query(TaxJdbcRepository::transaction)
                .optional();
    }

    @Override
    public Optional<Transaction> find(String reference) {
        return jdbc.sql("select " + TRANSACTION_COLUMNS + " from payments.tax_transactions where reference = :ref")
                .param("ref", reference)
                .query(TaxJdbcRepository::transaction)
                .optional();
    }

    @Override
    public void recorded(
            String reference,
            String stripeTransaction,
            @Nullable Long stripeTaxCents,
            @Nullable String calculationId,
            Instant at) {
        jdbc.sql("""
                        update payments.tax_transactions
                           set state = 'recorded', stripe_transaction = :stripe, stripe_tax_cents = :stripeTax,
                               calculation_id = coalesce(:calculation, calculation_id), recorded_at = :at,
                               attempts = attempts + 1, error = null
                         where reference = :ref""")
                .param("stripe", stripeTransaction)
                .param("stripeTax", stripeTaxCents)
                .param("calculation", calculationId)
                .param("at", ts(at))
                .param("ref", reference)
                .update();
    }

    @Override
    public void failed(String reference, String error) {
        jdbc.sql("""
                        update payments.tax_transactions
                           set state = 'failed', attempts = attempts + 1, error = left(:error, 2000)
                         where reference = :ref and state in ('pending', 'failed')""").param("error", error).param("ref", reference).update();
    }

    @Override
    public List<String> pending(int maxAttempts, int limit) {
        return jdbc.sql("""
                        select reference from payments.tax_transactions
                         where state in ('pending', 'failed') and attempts < :max
                         order by created_at, (kind = 'reversal') limit :limit""")
                .param("max", maxAttempts)
                .param("limit", limit)
                .query((rs, _) -> rs.getString("reference"))
                .list();
    }

    @Override
    public List<Transaction> unreconciled(String period, int limit) {
        return jdbc.sql("select " + TRANSACTION_COLUMNS + """
                         from payments.tax_transactions
                         where period = :period and state = 'recorded' and reconciled_at is null
                         order by recorded_at limit :limit""")
                .param("period", period)
                .param("limit", limit)
                .query(TaxJdbcRepository::transaction)
                .list();
    }

    @Override
    public void reconciled(String reference, long stripeTaxCents, Instant at) {
        jdbc.sql("""
                        update payments.tax_transactions set stripe_tax_cents = :tax, reconciled_at = :at
                         where reference = :ref""")
                .param("tax", stripeTaxCents)
                .param("at", ts(at))
                .param("ref", reference)
                .update();
    }

    private static Transaction transaction(ResultSet rs, int row) throws SQLException {
        return new Transaction(
                rs.getString("id"),
                rs.getString("reference"),
                CodedEnum.fromCode(Kind.class, rs.getString("kind")),
                rs.getString("merchant_id"),
                rs.getString("escrow_id"),
                CodedEnum.fromCode(EscrowKind.class, rs.getString("escrow_kind")),
                rs.getString("original_reference"),
                rs.getString("calculation_id"),
                Province.valueOf(rs.getString("province")),
                rs.getLong("amount_cents"),
                rs.getLong("tax_cents"),
                rs.getString("period"),
                requiredInstant(rs, "occurred_at"),
                CodedEnum.fromCode(State.class, rs.getString("state")),
                rs.getString("stripe_transaction"),
                rs.getObject("stripe_tax_cents", Long.class),
                rs.getInt("attempts"));
    }

    // ── read model ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void refreshTotals(TotalsKey key, Instant at) {
        // one refresh of a row at a time (the listener, the job and the reconciliation may meet here)
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))")
                .param("k", "tax-totals:" + key.merchantId() + ":" + key.period() + ":" + key.jurisdiction())
                .query((rs, _) -> 1)
                .list();
        var base = jdbc.sql(
                        "select base_cents from payments.tax_totals_sync where merchant_id = :merchantId and period = :period and jurisdiction = :jurisdiction")
                .param("merchantId", key.merchantId())
                .param("period", key.period())
                .param("jurisdiction", key.jurisdiction())
                .query(Long.class)
                .optional()
                // first time the sync touches this row: what it showed before (dev seed) is the base
                .orElseGet(() -> jdbc.sql("""
                                select collected_cents from payments.tax_jurisdiction_totals
                                 where merchant_id = :merchantId and period = :period and jurisdiction = :jurisdiction""")
                        .param("merchantId", key.merchantId())
                        .param("period", key.period())
                        .param("jurisdiction", key.jurisdiction())
                        .query(Long.class)
                        .optional()
                        .orElse(0L));
        jdbc.sql("""
                        with t as (
                          select coalesce(sum(tax_cents) filter (where kind = 'sale'), 0)     as sales,
                                 coalesce(sum(tax_cents) filter (where kind = 'reversal'), 0) as reversed,
                                 count(*)                                                     as n
                            from payments.tax_transactions
                           where merchant_id = :merchantId and period = :period and jurisdiction = :jurisdiction and state = 'recorded'),
                        sync as (
                          insert into payments.tax_totals_sync as s (merchant_id, period, jurisdiction, base_cents,
                                 sales_tax_cents, reversed_tax_cents, transaction_count, synced_at)
                          select :merchantId, :period, :jurisdiction, :base, t.sales, t.reversed, t.n, :at from t
                          on conflict (merchant_id, period, jurisdiction) do update
                             set sales_tax_cents = excluded.sales_tax_cents,
                                 reversed_tax_cents = excluded.reversed_tax_cents,
                                 transaction_count = excluded.transaction_count,
                                 synced_at = excluded.synced_at
                          returning base_cents, sales_tax_cents, reversed_tax_cents, transaction_count)
                        insert into payments.tax_jurisdiction_totals as r
                               (merchant_id, period, jurisdiction, collected_cents, handling, updated_at)
                        select :merchantId, :period, :jurisdiction, greatest(0, base_cents + sales_tax_cents - reversed_tax_cents),
                               'remitted_by_northline', :at
                          from sync
                        on conflict (merchant_id, period, jurisdiction) do update
                           set collected_cents = excluded.collected_cents,
                               handling = case when (select transaction_count from sync) > 0
                                               then 'remitted_by_northline' else r.handling end,
                               updated_at = excluded.updated_at""")
                .param("merchantId", key.merchantId())
                .param("period", key.period())
                .param("jurisdiction", key.jurisdiction())
                .param("base", base)
                .param("at", ts(at))
                .update();
    }

    @Override
    public List<TotalsKey> totalsOf(String period) {
        return jdbc.sql("""
                        select distinct merchant_id, period, jurisdiction from payments.tax_transactions
                         where period = :period and state = 'recorded'
                        union
                        select merchant_id, period, jurisdiction from payments.tax_totals_sync where period = :period""")
                .param("period", period)
                .query((rs, _) -> new TotalsKey(
                        rs.getString("merchant_id"), rs.getString("period"), rs.getString("jurisdiction")))
                .list();
    }

    @Override
    public Optional<Province> merchantProvince(String merchantId) {
        return jdbc.sql("select province from merchants.merchants where id = :id and province is not null")
                .param("id", merchantId)
                .query(String.class)
                .optional()
                .flatMap(Province::of);
    }
}
