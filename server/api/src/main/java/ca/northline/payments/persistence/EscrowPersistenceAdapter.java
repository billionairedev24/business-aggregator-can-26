package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.application.EscrowRepository;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.payments.domain.Escrow;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class EscrowPersistenceAdapter implements EscrowRepository {

    private static final String INTENT_COLUMNS = """
            id, stripe_pi, state, customer_id, amount_cents, stripe_customer, payment_method_ref, stripe_charge,
            transfer_group, ref_type, ref_id, merchant_id, authorized_at, capture_before, reauthorizations,
            reauth_failed_at""";

    private final EscrowRowRepository rows;
    private final PaymentsRowMapper mapper;
    private final JdbcClient jdbc;

    @Override
    public Optional<Escrow> findById(String id) {
        return rows.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Escrow> findByRef(String refType, String refId) {
        return rows.findByRefTypeAndRefId(refType, refId).map(mapper::toDomain);
    }

    @Override
    public Optional<Escrow> findByRefForUpdate(String refType, String refId) {
        return rows.lockByRef(refType, refId).map(mapper::toDomain);
    }

    @Override
    public Optional<Escrow> findByPaymentIntentId(String paymentIntentId) {
        return rows.findByPaymentIntentId(paymentIntentId).map(mapper::toDomain);
    }

    @Override
    public List<Escrow> releasable(Instant now, int limit) {
        return rows.releasable(now, limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Escrow> authorizationsLapsingBefore(Instant before, int limit) {
        return rows.authorizationsLapsingBefore(before, limit).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public String recordPaymentIntent(IntentRecord intent) {
        return jdbc.sql("""
                        insert into payments.payment_intents
                               (id, stripe_pi, customer_id, amount_cents, currency, capture_method, state, stripe_customer,
                                payment_method_ref, stripe_charge, transfer_group, ref_type, ref_id, merchant_id,
                                authorized_at, capture_before, reauthorizations)
                        values (:id, :pi, :customer, :amount, 'CAD', 'manual', :state, :stripeCustomer, :pm, :charge,
                                :group, :refType, :refId, :merchant, :authorizedAt, :captureBefore, :reauths)
                        on conflict (stripe_pi) do update
                           set state = excluded.state,
                               stripe_customer = coalesce(excluded.stripe_customer, payment_intents.stripe_customer),
                               payment_method_ref = coalesce(excluded.payment_method_ref, payment_intents.payment_method_ref),
                               stripe_charge = coalesce(excluded.stripe_charge, payment_intents.stripe_charge),
                               transfer_group = coalesce(excluded.transfer_group, payment_intents.transfer_group),
                               ref_type = coalesce(excluded.ref_type, payment_intents.ref_type),
                               ref_id = coalesce(excluded.ref_id, payment_intents.ref_id),
                               merchant_id = coalesce(excluded.merchant_id, payment_intents.merchant_id),
                               authorized_at = coalesce(payment_intents.authorized_at, excluded.authorized_at),
                               capture_before = coalesce(excluded.capture_before, payment_intents.capture_before)
                        returning id""")
                .param("id", Ids.next())
                .param("pi", intent.stripePaymentIntent())
                .param("customer", intent.customerId())
                .param("amount", intent.amountCents())
                .param("state", intent.state().code())
                .param("stripeCustomer", intent.stripeCustomer())
                .param("pm", intent.paymentMethod())
                .param("charge", intent.charge())
                .param("group", intent.transferGroup())
                .param("refType", intent.refType())
                .param("refId", intent.refId())
                .param("merchant", intent.merchantId())
                .param("authorizedAt", ts(intent.authorizedAt()))
                .param("captureBefore", ts(intent.captureBefore()))
                .param("reauths", intent.reauthorizations())
                .query(String.class)
                .single();
    }

    @Override
    public void markPaymentIntent(String paymentIntentId, IntentStatus state) {
        jdbc.sql("update payments.payment_intents set state = :state where id = :id")
                .param("state", state.code())
                .param("id", paymentIntentId)
                .update();
    }

    @Override
    public void markAuthorized(String paymentIntentId, Instant at) {
        jdbc.sql("""
                        update payments.payment_intents
                           set state = 'authorized', authorized_at = coalesce(authorized_at, :at) where id = :id""").param("at", ts(at)).param("id", paymentIntentId).update();
    }

    @Override
    public void recordCapture(String paymentIntentId, @Nullable String stripeCharge) {
        jdbc.sql("""
                        update payments.payment_intents
                           set state = 'captured', stripe_charge = coalesce(:charge, stripe_charge)
                         where id = :id""").param("charge", stripeCharge).param("id", paymentIntentId).update();
    }

    @Override
    public Optional<Intent> intent(String paymentIntentId) {
        return jdbc.sql("select " + INTENT_COLUMNS + " from payments.payment_intents where id = :id")
                .param("id", paymentIntentId)
                .query(EscrowPersistenceAdapter::intent)
                .optional();
    }

    @Override
    public Optional<Intent> intentByStripeId(String stripePaymentIntent) {
        return jdbc.sql("select " + INTENT_COLUMNS + " from payments.payment_intents where stripe_pi = :pi")
                .param("pi", stripePaymentIntent)
                .query(EscrowPersistenceAdapter::intent)
                .optional();
    }

    @Override
    public Optional<Intent> currentIntentForUpdate(String refType, String refId) {
        return jdbc.sql("select " + INTENT_COLUMNS + """
                         from payments.payment_intents
                        where ref_type = :type and ref_id = :id and replaced_by is null
                        order by created_at desc, id desc
                        limit 1
                        for update
                        """)
                .param("type", refType)
                .param("id", refId)
                .query(EscrowPersistenceAdapter::intent)
                .optional();
    }

    private static Intent intent(ResultSet rs, int rowNum) throws SQLException {
        return new Intent(
                rs.getString("id"),
                rs.getString("stripe_pi"),
                CodedEnum.fromCode(IntentStatus.class, rs.getString("state")),
                rs.getString("customer_id"),
                rs.getLong("amount_cents"),
                rs.getString("stripe_customer"),
                rs.getString("payment_method_ref"),
                rs.getString("stripe_charge"),
                rs.getString("transfer_group"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("merchant_id"),
                instant(rs, "authorized_at"),
                instant(rs, "capture_before"),
                rs.getInt("reauthorizations"),
                instant(rs, "reauth_failed_at"));
    }

    @Override
    public void replacePaymentIntent(String oldPaymentIntentId, String newPaymentIntentId) {
        jdbc.sql("update payments.payment_intents set state = 'canceled', replaced_by = :new where id = :old")
                .param("new", newPaymentIntentId)
                .param("old", oldPaymentIntentId)
                .update();
    }

    @Override
    public void reauthorizationFailed(String paymentIntentId, Instant at) {
        jdbc.sql("update payments.payment_intents set reauth_failed_at = :at where id = :id")
                .param("at", ts(at))
                .param("id", paymentIntentId)
                .update();
    }

    @Override
    public Optional<String> stripeCustomer(String customerId) {
        return jdbc.sql("select stripe_customer from payments.stripe_customers where customer_id = :id")
                .param("id", customerId)
                .query(String.class)
                .optional();
    }

    @Override
    public void saveStripeCustomer(String customerId, String stripeCustomer) {
        jdbc.sql("""
                        insert into payments.stripe_customers (customer_id, stripe_customer) values (:id, :cus)
                        on conflict (customer_id) do nothing""").param("id", customerId).param("cus", stripeCustomer).update();
    }

    @Override
    public Optional<String> receiptLocale(String customerId) {
        return jdbc.sql("select receipt_locale from payments.stripe_customers where customer_id = :id")
                .param("id", customerId)
                .query((rs, _) -> Optional.ofNullable(rs.getString(1)))
                .optional()
                .flatMap(o -> o);
    }

    @Override
    public void saveReceiptLocale(String customerId, String locale) {
        jdbc.sql("update payments.stripe_customers set receipt_locale = :locale where customer_id = :id")
                .param("id", customerId)
                .param("locale", locale)
                .update();
    }

    @Override
    public void insert(Escrow escrow) {
        rows.save(mapper.toRow(escrow));
    }

    @Override
    public void update(Escrow escrow) {
        rows.save(mapper.toRow(escrow));
    }

    @Override
    public void recordTransfer(
            String escrowId,
            String stripeTransfer,
            String transferGroup,
            long grossCents,
            long feeCents,
            long netCents,
            Instant at) {
        jdbc.sql("""
                        insert into payments.transfers
                               (id, escrow_id, stripe_transfer, transfer_group, gross_cents, fee_cents, net_cents, at)
                        values (:id, :escrow, :tr, :group, :gross, :fee, :net, :at)
                        on conflict (stripe_transfer) do nothing""")
                .param("id", Ids.next())
                .param("escrow", escrowId)
                .param("tr", stripeTransfer)
                .param("group", transferGroup)
                .param("gross", grossCents)
                .param("fee", feeCents)
                .param("net", netCents)
                .param("at", ts(at))
                .update();
    }

    @Override
    public Optional<TransferRecord> transferOf(String escrowId) {
        return jdbc.sql("""
                        select id, stripe_transfer, net_cents, reversed_cents from payments.transfers
                         where escrow_id = :escrow order by at desc limit 1""")
                .param("escrow", escrowId)
                .query((rs, _) -> new TransferRecord(
                        rs.getString("id"),
                        rs.getString("stripe_transfer"),
                        rs.getLong("net_cents"),
                        rs.getLong("reversed_cents")))
                .optional();
    }

    @Override
    public void addReversal(String transferId, long cents) {
        jdbc.sql("update payments.transfers set reversed_cents = reversed_cents + :cents where id = :id")
                .param("cents", cents)
                .param("id", transferId)
                .update();
    }

    @Override
    public boolean syncReversed(String stripeTransfer, long amountReversedCents) {
        return jdbc.sql("""
                        update payments.transfers set reversed_cents = greatest(reversed_cents, :cents)
                         where stripe_transfer = :tr""")
                        .param("cents", amountReversedCents)
                        .param("tr", stripeTransfer)
                        .update()
                > 0;
    }
}
