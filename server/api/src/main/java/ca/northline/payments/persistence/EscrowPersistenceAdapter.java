package ca.northline.payments.persistence;

import ca.northline.payments.application.EscrowRepository;
import ca.northline.payments.domain.Escrow;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class EscrowPersistenceAdapter implements EscrowRepository {

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
    public List<Escrow> releasable(Instant now, int limit) {
        return rows.releasable(now, limit).stream().map(mapper::toDomain).toList();
    }

    @Override
    public String recordPaymentIntent(String stripePaymentIntent, String customerId, long amountCents) {
        var existing = jdbc.sql("select id from payments.payment_intents where stripe_pi = :pi")
                .param("pi", stripePaymentIntent)
                .query(String.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = Ids.next();
        jdbc.sql("""
                        insert into payments.payment_intents
                               (id, stripe_pi, customer_id, amount_cents, currency, capture_method, state)
                        values (:id, :pi, :customer, :amount, 'CAD', 'manual', 'authorized')""")
                .param("id", id)
                .param("pi", stripePaymentIntent)
                .param("customer", customerId)
                .param("amount", amountCents)
                .update();
        return id;
    }

    @Override
    public void markPaymentIntent(String paymentIntentId, String state) {
        jdbc.sql("update payments.payment_intents set state = :state where id = :id")
                .param("state", state)
                .param("id", paymentIntentId)
                .update();
    }

    @Override
    public Optional<String> stripePaymentIntent(String paymentIntentId) {
        return jdbc.sql("select stripe_pi from payments.payment_intents where id = :id")
                .param("id", paymentIntentId)
                .query(String.class)
                .optional();
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
            String escrowId, String stripeTransfer, long grossCents, long feeCents, long netCents, Instant at) {
        jdbc.sql("""
                        insert into payments.transfers (id, escrow_id, stripe_transfer, gross_cents, fee_cents, net_cents, at)
                        values (:id, :escrow, :tr, :gross, :fee, :net, :at)""")
                .param("id", Ids.next())
                .param("escrow", escrowId)
                .param("tr", stripeTransfer)
                .param("gross", grossCents)
                .param("fee", feeCents)
                .param("net", netCents)
                .param("at", at.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }
}
