package ca.northline.payments.persistence;

import ca.northline.payments.application.CaseRepository;
import ca.northline.payments.domain.Dispute;
import ca.northline.payments.domain.Evidence;
import ca.northline.payments.domain.Refund;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refund cases (Spring Data JDBC) and disputes ({@code JdbcClient}: the evidence list is {@code jsonb}, which Spring
 * Data JDBC can't bind without global converters).
 */
@Repository
@RequiredArgsConstructor
class CasePersistenceAdapter implements CaseRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Evidence>> EVIDENCE = new TypeReference<>() {};
    private static final String DISPUTE_COLUMNS = """
            id, ref_id, merchant_id, case_number, subject, amount_cents, customer_name, customer_statement, opened_by,
            evidence, response, response_updated_at, offer_cents, offer_state, offer_expires_at, state, decision,
            decided_by, refund_cents, respond_by, opened_at, decided_at, stripe_dispute, stripe_status, stripe_reason,
            stripe_updated_at, version""";

    private final RefundRowRepository refunds;
    private final PaymentsRowMapper mapper;
    private final JdbcClient jdbc;

    @Override
    public String nextCaseNumber(String prefix) {
        var n = jdbc.sql("select nextval('payments.case_numbers')")
                .query(Long.class)
                .single();
        return prefix + "-" + n;
    }

    @Override
    public Optional<Refund> refund(String merchantId, String refundId) {
        return refunds.findByIdAndMerchantId(refundId, merchantId).map(mapper::toDomain);
    }

    @Override
    public Optional<Refund> refund(String refundId) {
        return refunds.findById(refundId).map(mapper::toDomain);
    }

    @Override
    public void insert(Refund refund) {
        refunds.save(mapper.toRow(refund));
    }

    @Override
    public void update(Refund refund) {
        refunds.save(mapper.toRow(refund));
    }

    @Override
    public List<Refund> lapsedRefunds(Instant now, int limit) {
        return refunds.lapsed(now, limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Refund> approvedRefunds(int limit) {
        return refunds.approved(limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Refund> refunds(String merchantId, int limit) {
        return refunds.latest(merchantId, limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<Dispute> dispute(String merchantId, String disputeId) {
        return jdbc.sql("select " + DISPUTE_COLUMNS + " from payments.disputes where id = :id and merchant_id = :m")
                .param("id", disputeId)
                .param("m", merchantId)
                .query(CasePersistenceAdapter::dispute)
                .optional();
    }

    @Override
    public Optional<Dispute> dispute(String disputeId) {
        return jdbc.sql("select " + DISPUTE_COLUMNS + " from payments.disputes where id = :id")
                .param("id", disputeId)
                .query(CasePersistenceAdapter::dispute)
                .optional();
    }

    @Override
    public void insert(Dispute d) {
        jdbc.sql("""
                        insert into payments.disputes
                               (id, ref_type, ref_id, merchant_id, case_number, subject, amount_cents, customer_name,
                                customer_statement, opened_by, evidence, state, respond_by, opened_at, stripe_dispute,
                                stripe_status, stripe_reason, stripe_updated_at, version)
                        values (:id, 'escrow', :escrow, :merchant, :case, :subject, :amount, :customer,
                                :statement, :openedBy, cast(:evidence as jsonb), :state, :respondBy, :openedAt,
                                :stripeDispute, :stripeStatus, :stripeReason, :stripeUpdatedAt, 0)""")
                .param("stripeDispute", d.getStripeDispute())
                .param("stripeStatus", d.getStripeStatus())
                .param("stripeReason", d.getStripeReason())
                .param("stripeUpdatedAt", ts(d.getStripeUpdatedAt()))
                .param("id", d.getId())
                .param("escrow", d.getEscrowId())
                .param("merchant", d.getMerchantId())
                .param("case", d.getCaseNumber())
                .param("subject", d.getSubject())
                .param("amount", d.getAmountCents())
                .param("customer", d.getCustomerName())
                .param("statement", d.getCustomerStatement())
                .param("openedBy", d.getOpenedBy())
                .param("evidence", JSON.writeValueAsString(d.getEvidence()))
                .param("state", d.getState().code())
                .param("respondBy", ts(d.getRespondBy()))
                .param("openedAt", ts(d.getOpenedAt()))
                .update();
    }

    @Override
    public void update(Dispute d) {
        var version = d.getVersion() == null ? 0 : d.getVersion();
        var updated = jdbc.sql("""
                        update payments.disputes
                           set evidence = cast(:evidence as jsonb), response = :response,
                               response_updated_at = :responseAt, offer_cents = :offer, offer_state = :offerState,
                               offer_expires_at = :offerExpires, state = :state, decision = :decision,
                               decided_by = :decidedBy, refund_cents = :refund, decided_at = :decidedAt,
                               respond_by = :respondBy, stripe_dispute = :stripeDispute, stripe_status = :stripeStatus,
                               stripe_reason = :stripeReason, stripe_updated_at = :stripeUpdatedAt,
                               version = version + 1
                         where id = :id and version = :version""")
                .param("evidence", JSON.writeValueAsString(d.getEvidence()))
                .param("response", d.getResponse())
                .param("responseAt", ts(d.getResponseUpdatedAt()))
                .param("offer", d.getOfferCents())
                .param("offerState", CodedEnums.toCode(d.getOfferState()))
                .param("offerExpires", ts(d.getOfferExpiresAt()))
                .param("state", d.getState().code())
                .param("decision", CodedEnums.toCode(d.getDecision()))
                .param("decidedBy", d.getDecidedBy())
                .param("refund", d.getRefundCents())
                .param("decidedAt", ts(d.getDecidedAt()))
                .param("respondBy", ts(d.getRespondBy()))
                .param("stripeDispute", d.getStripeDispute())
                .param("stripeStatus", d.getStripeStatus())
                .param("stripeReason", d.getStripeReason())
                .param("stripeUpdatedAt", ts(d.getStripeUpdatedAt()))
                .param("id", d.getId())
                .param("version", version)
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("dispute " + d.getId() + " changed concurrently");
        }
    }

    @Override
    public Optional<Dispute> disputeByStripeId(String stripeDispute) {
        return jdbc.sql("select " + DISPUTE_COLUMNS + " from payments.disputes where stripe_dispute = :sd")
                .param("sd", stripeDispute)
                .query(CasePersistenceAdapter::dispute)
                .optional();
    }

    @Override
    public Optional<Dispute> openDisputeOn(String escrowId) {
        return jdbc.sql("select " + DISPUTE_COLUMNS
                        + " from payments.disputes where ref_id = :escrow and state <> 'decided'"
                        + " and stripe_dispute is null order by opened_at desc limit 1")
                .param("escrow", escrowId)
                .query(CasePersistenceAdapter::dispute)
                .optional();
    }

    @Override
    public boolean refundStripeStatus(String stripeRefund, String status) {
        return jdbc.sql("update payments.refunds set stripe_status = :status where stripe_refund = :re")
                        .param("status", status)
                        .param("re", stripeRefund)
                        .update()
                > 0;
    }

    @Override
    public List<Dispute> expiredOffers(Instant now, int limit) {
        return jdbc.sql("select " + DISPUTE_COLUMNS
                        + " from payments.disputes where offer_state = 'pending' and offer_expires_at <= :now"
                        + " order by offer_expires_at limit :limit")
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .param("limit", limit)
                .query(CasePersistenceAdapter::dispute)
                .list();
    }

    @Override
    public List<Dispute> disputes(String merchantId, int limit) {
        return jdbc.sql("select " + DISPUTE_COLUMNS
                        + " from payments.disputes where merchant_id = :m order by opened_at desc limit :limit")
                .param("m", merchantId)
                .param("limit", limit)
                .query(CasePersistenceAdapter::dispute)
                .list();
    }

    @Override
    public int awaitingMerchant(String merchantId) {
        return jdbc.sql("""
                        select (select count(*) from payments.disputes where merchant_id = :m and state = 'open')
                             + (select count(*) from payments.refunds where merchant_id = :m and state = 'seller_review')""").param("m", merchantId).query(Integer.class).single();
    }

    private static Dispute dispute(ResultSet rs, int row) throws SQLException {
        var evidenceJson = rs.getString("evidence");
        List<Evidence> evidence = evidenceJson == null || evidenceJson.isBlank()
                ? new ArrayList<>()
                : new ArrayList<>(JSON.readValue(evidenceJson, EVIDENCE));
        return Dispute.builder()
                .id(rs.getString("id"))
                .escrowId(rs.getString("ref_id"))
                .merchantId(rs.getString("merchant_id"))
                .caseNumber(rs.getString("case_number"))
                .subject(rs.getString("subject"))
                .amountCents(rs.getLong("amount_cents"))
                .customerName(rs.getString("customer_name"))
                .customerStatement(rs.getString("customer_statement"))
                .openedBy(rs.getString("opened_by"))
                .evidence(evidence)
                .response(rs.getString("response"))
                .responseUpdatedAt(instant(rs, "response_updated_at"))
                .offerCents(rs.getObject("offer_cents", Long.class))
                .offerState(CodedEnums.fromCode(rs.getString("offer_state"), Dispute.OfferState.class))
                .offerExpiresAt(instant(rs, "offer_expires_at"))
                .state(CodedEnum.fromCode(Dispute.State.class, rs.getString("state")))
                .decision(CodedEnums.fromCode(rs.getString("decision"), Dispute.Decision.class))
                .decidedBy(rs.getString("decided_by"))
                .refundCents(rs.getObject("refund_cents", Long.class))
                .respondBy(instant(rs, "respond_by"))
                .openedAt(java.util.Objects.requireNonNull(instant(rs, "opened_at")))
                .decidedAt(instant(rs, "decided_at"))
                .stripeDispute(rs.getString("stripe_dispute"))
                .stripeStatus(rs.getString("stripe_status"))
                .stripeReason(rs.getString("stripe_reason"))
                .stripeUpdatedAt(instant(rs, "stripe_updated_at"))
                .version(rs.getInt("version"))
                .build();
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static java.time.@Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }
}
