package ca.northline.booking.persistence;

import ca.northline.booking.application.QuoteRepository;
import ca.northline.booking.domain.Quote;
import ca.northline.booking.domain.QuoteContent;
import ca.northline.booking.domain.QuoteEnums.DepositKind;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import ca.northline.booking.domain.QuoteEnums.QuoteState;
import ca.northline.booking.domain.QuoteEnums.Warranty;
import ca.northline.booking.domain.QuoteLine;
import ca.northline.booking.domain.QuoteTotals;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Quotes with their lines, via {@link JdbcClient}: Spring Data JDBC rewrites child collections on every save, which the
 * V040 immutability triggers (rightly) reject for sent quotes. Content and lines are written only while the quote is a
 * draft; {@link #updateLifecycle} touches nothing but state / sent_at / valid_until. Totals are re-derived from the lines and the
 * stored tax rate on read ({@link QuoteTotals}).
 */
@Repository
@RequiredArgsConstructor
class QuotePersistenceAdapter implements QuoteRepository {

    private static final String COLUMNS = """
            id, request_id, merchant_id, ref, version, scope, exclusions, proposed_at, duration_min, warranty,
            deposit_kind, deposit_bps, subtotal_cents, tax_cents, tax_bps, total_cents, deposit_cents, attachments,
            valid_hours, valid_until, state, viewed_at, created_at, created_by, sent_at
            """;

    private final JdbcClient jdbc;

    @Override
    public Optional<Quote> find(String merchantId, String quoteId) {
        return jdbc.sql("select " + COLUMNS + " from booking.quotes where id = :id and merchant_id = :merchantId")
                .param("id", quoteId)
                .param("merchantId", merchantId)
                .query((rs, _) -> header(rs))
                .optional()
                .map(this::withLines);
    }

    @Override
    public Optional<Quote> findById(String quoteId) {
        return jdbc.sql("select " + COLUMNS + " from booking.quotes where id = :id")
                .param("id", quoteId)
                .query((rs, _) -> header(rs))
                .optional()
                .map(this::withLines);
    }

    @Override
    public List<Quote> current(String merchantId, Collection<String> requestIds) {
        if (requestIds.isEmpty()) {
            return List.of();
        }
        return jdbc
                .sql("select distinct on (request_id) " + COLUMNS + """
                          from booking.quotes where merchant_id = :merchantId and request_id in (:ids)
                         order by request_id, version desc
                        """)
                .param("merchantId", merchantId)
                .param("ids", List.copyOf(requestIds))
                .query((rs, _) -> header(rs))
                .list()
                .stream()
                .map(this::withLines)
                .toList();
    }

    @Override
    public List<Quote> sentOf(String requestId) {
        return jdbc.sql("select " + COLUMNS + """
                          from booking.quotes where request_id = :id and state <> 'draft'
                         order by merchant_id, version desc
                        """).param("id", requestId).query((rs, _) -> header(rs)).list().stream()
                .map(this::withLines)
                .toList();
    }

    @Override
    public void insertDraft(Quote quote) {
        var c = quote.getContent();
        var t = quote.getTotals();
        jdbc.sql("""
                        insert into booking.quotes (id, request_id, merchant_id, ref, version, scope, exclusions, proposed_at,
                               duration_min, warranty, deposit_kind, deposit_bps, subtotal_cents, tax_cents, tax_bps, total_cents,
                               deposit_cents, attachments, valid_hours, state, created_at, created_by)
                        values (:id, :requestId, :merchantId, :ref, :version, :scope, :exclusions, :proposedAt,
                               :durationMin, :warranty, :depositKind, :depositBps, :subtotal, :tax, :taxBps, :total,
                               :deposit, cast(:attachments as text[]), :validHours, 'draft', :createdAt, :createdBy)
                        """)
                .param("id", quote.getId())
                .param("requestId", quote.getRequestId())
                .param("merchantId", quote.getMerchantId())
                .param("ref", quote.getRef())
                .param("version", quote.getVersion())
                .param("scope", c.scope())
                .param("exclusions", c.exclusions(), Types.VARCHAR)
                .param("proposedAt", JdbcTimes.ts(c.proposedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("durationMin", c.durationMin(), Types.INTEGER)
                .param("warranty", c.warranty().code())
                .param("depositKind", c.depositKind().code())
                .param("depositBps", c.depositBps(), Types.INTEGER)
                .param("subtotal", t.subtotalCents())
                .param("tax", t.taxCents())
                .param("taxBps", t.taxBps())
                .param("total", t.totalCents())
                .param("deposit", t.depositCents())
                .param("attachments", c.attachments().toArray(String[]::new))
                .param("validHours", c.validHours())
                .param("createdAt", JdbcTimes.ts(quote.getCreatedAt()))
                .param("createdBy", quote.getCreatedBy())
                .update();
        insertLines(quote);
    }

    @Override
    public void updateDraft(Quote quote) {
        var c = quote.getContent();
        var t = quote.getTotals();
        jdbc.sql("delete from booking.quote_lines where quote_id = :id")
                .param("id", quote.getId())
                .update();
        jdbc.sql("""
                        update booking.quotes set scope = :scope, exclusions = :exclusions, proposed_at = :proposedAt,
                               duration_min = :durationMin, warranty = :warranty, deposit_kind = :depositKind,
                               deposit_bps = :depositBps, subtotal_cents = :subtotal, tax_cents = :tax, tax_bps = :taxBps, total_cents = :total,
                               deposit_cents = :deposit, attachments = cast(:attachments as text[]), valid_hours = :validHours
                         where id = :id and state = 'draft'
                        """)
                .param("id", quote.getId())
                .param("scope", c.scope())
                .param("exclusions", c.exclusions(), Types.VARCHAR)
                .param("proposedAt", JdbcTimes.ts(c.proposedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("durationMin", c.durationMin(), Types.INTEGER)
                .param("warranty", c.warranty().code())
                .param("depositKind", c.depositKind().code())
                .param("depositBps", c.depositBps(), Types.INTEGER)
                .param("subtotal", t.subtotalCents())
                .param("tax", t.taxCents())
                .param("taxBps", t.taxBps())
                .param("total", t.totalCents())
                .param("deposit", t.depositCents())
                .param("attachments", c.attachments().toArray(String[]::new))
                .param("validHours", c.validHours())
                .update();
        insertLines(quote);
    }

    @Override
    public void updateLifecycle(Quote quote) {
        jdbc.sql("""
                        update booking.quotes set state = :state, sent_at = :sentAt, valid_until = :validUntil
                         where id = :id
                        """)
                .param("id", quote.getId())
                .param("state", quote.getState().code())
                .param("sentAt", JdbcTimes.ts(quote.getSentAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("validUntil", JdbcTimes.ts(quote.getValidUntil()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private void insertLines(Quote quote) {
        var lines = quote.getContent().lines();
        for (int i = 0; i < lines.size(); i++) {
            var l = lines.get(i);
            jdbc.sql("""
                            insert into booking.quote_lines (id, quote_id, position, kind, description, note, qty, unit_cents,
                                   amount_cents, taxable)
                            values (:id, :quoteId, :position, :kind, :description, :note, :qty, :unit, :amount, :taxable)
                            """)
                    .param("id", Ids.next())
                    .param("quoteId", quote.getId())
                    .param("position", i)
                    .param("kind", l.kind().code())
                    .param("description", l.description())
                    .param("note", l.note(), Types.VARCHAR)
                    .param("qty", l.qty())
                    .param("unit", l.unitCents())
                    .param("amount", l.amountCents())
                    .param("taxable", l.taxable())
                    .update();
        }
    }

    /** Header columns; lines and totals are filled in by {@link #withLines}. */
    private static Header header(ResultSet rs) throws SQLException {
        int durationValue = rs.getInt("duration_min");
        Integer duration = rs.wasNull() ? null : durationValue;
        int bpsValue = rs.getInt("deposit_bps");
        Integer depositBps = rs.wasNull() ? null : bpsValue;
        return new Header(
                rs.getString("id"),
                rs.getString("request_id"),
                rs.getString("merchant_id"),
                rs.getString("ref"),
                rs.getInt("version"),
                rs.getString("scope"),
                rs.getString("exclusions"),
                JdbcTimes.instant(rs, "proposed_at"),
                duration,
                CodedEnum.fromCode(Warranty.class, rs.getString("warranty")),
                CodedEnum.fromCode(DepositKind.class, rs.getString("deposit_kind")),
                depositBps,
                rs.getLong("subtotal_cents"),
                rs.getLong("tax_cents"),
                rs.getInt("tax_bps"),
                strings(rs.getArray("attachments")),
                rs.getInt("valid_hours"),
                JdbcTimes.instant(rs, "valid_until"),
                CodedEnum.fromCode(QuoteState.class, rs.getString("state")),
                JdbcTimes.instant(rs, "viewed_at"),
                JdbcTimes.requiredInstant(rs, "created_at"),
                java.util.Objects.requireNonNullElse(rs.getString("created_by"), ""),
                JdbcTimes.instant(rs, "sent_at"));
    }

    private Quote withLines(Header h) {
        var lines = jdbc.sql("""
                        select kind, description, note, qty, unit_cents, coalesce(taxable, true) as taxable
                          from booking.quote_lines where quote_id = :id order by position
                        """)
                .param("id", h.id())
                .query((rs, _) -> new QuoteLine(
                        CodedEnum.fromCode(LineKind.class, rs.getString("kind")),
                        rs.getString("description"),
                        rs.getString("note"),
                        rs.getBigDecimal("qty"),
                        rs.getLong("unit_cents"),
                        rs.getBoolean("taxable")))
                .list();
        var content = new QuoteContent(
                lines,
                h.scope(),
                h.exclusions(),
                h.proposedAt(),
                h.durationMin(),
                h.validHours(),
                h.warranty(),
                h.depositKind(),
                h.depositBps(),
                h.attachments());
        return Quote.builder()
                .id(h.id())
                .requestId(h.requestId())
                .merchantId(h.merchantId())
                .ref(h.ref())
                .version(h.version())
                .content(content)
                .totals(QuoteTotals.of(content, h.taxBps()))
                .state(h.state())
                .createdBy(h.createdBy())
                .createdAt(h.createdAt())
                .sentAt(h.sentAt())
                .validUntil(h.validUntil())
                .viewedAt(h.viewedAt())
                .build();
    }

    private record Header(
            String id,
            String requestId,
            String merchantId,
            String ref,
            int version,
            String scope,
            @Nullable String exclusions,
            @Nullable Instant proposedAt,
            @Nullable Integer durationMin,
            Warranty warranty,
            DepositKind depositKind,
            @Nullable Integer depositBps,
            long subtotalCents,
            long taxCents,
            int taxBps,
            List<String> attachments,
            int validHours,
            @Nullable Instant validUntil,
            QuoteState state,
            @Nullable Instant viewedAt,
            Instant createdAt,
            String createdBy,
            @Nullable Instant sentAt) {}

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
