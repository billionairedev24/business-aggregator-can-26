package ca.northline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.booking.api.BookingProgressed.BookingCompleted;
import ca.northline.food.api.FoodOrderHandedOff;
import ca.northline.payments.api.EscrowKind;
import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.shared.Ids;
import ca.northline.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Fulfilment events from booking and food start the escrow release clock (integration pass). */
@Import(PaymentsFixture.class)
class EscrowFulfilmentEventsTest extends IntegrationTest {

    @Autowired
    PaymentsFixture fx;

    @Autowired
    EscrowLifecycle escrows;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    private String hold(String merchantId, EscrowKind kind, String refType, String refId) {
        return escrows.hold(new EscrowLifecycle.Hold(
                merchantId,
                kind,
                refType,
                refId,
                5_000,
                250,
                "cust-" + Ids.next(),
                "M. Tran",
                "Job",
                null,
                null,
                null,
                null,
                "pi_" + Ids.next(),
                Instant.now()));
    }

    private Instant releaseAt(String escrowId) {
        return jdbc.sql("select release_at from payments.escrows where id = ?")
                .params(escrowId)
                .query((rs, _) ->
                        rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .optional()
                .orElse(null);
    }

    private String state(String escrowId) {
        return jdbc.sql("select state from payments.escrows where id = ?")
                .params(escrowId)
                .query(String.class)
                .single();
    }

    @Test
    void bookingCompleted_startsThe48hClock() {
        var shop = fx.shop("provider", "master");
        var bookingId = Ids.next();
        var escrowId = hold(shop.merchantId(), EscrowKind.SERVICE, "booking", bookingId);
        var at = Instant.now().truncatedTo(ChronoUnit.MICROS);

        tx.executeWithoutResult(_ -> publisher.publishEvent(
                new BookingCompleted(Ids.next(), at, bookingId, shop.merchantId(), shop.ownerId(), 2)));

        await().atMost(Duration.ofSeconds(10)).until(() -> releaseAt(escrowId) != null);
        assertThat(Duration.between(at, releaseAt(escrowId))).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void foodHandoff_releasesEveryLineOfTheOrder() {
        var shop = fx.shop("kitchen", "trusted");
        var orderId = Ids.next();
        var line1 = Ids.next();
        var line2 = Ids.next();
        jdbc.sql("insert into orders.orders (id) values (?)").params(orderId).update();
        for (var line : new String[] {line1, line2}) {
            jdbc.sql(
                            "insert into orders.order_lines (id, order_id, merchant_id, qty, unit_cents) values (?, ?, ?, 1, 2500)")
                    .params(line, orderId, shop.merchantId())
                    .update();
        }
        var e1 = hold(shop.merchantId(), EscrowKind.FOOD, "order_line", line1);
        var e2 = hold(shop.merchantId(), EscrowKind.FOOD, "order_line", line2);

        tx.executeWithoutResult(_ -> publisher.publishEvent(new FoodOrderHandedOff(
                Ids.next(), Instant.now(), orderId, shop.merchantId(), shop.ownerId(), "delivery")));

        await().atMost(Duration.ofSeconds(10)).until(() -> releaseAt(e1) != null && releaseAt(e2) != null);
        assertThat(state(e1)).isIn("held", "released");
    }

    @Test
    void workWithoutEscrow_isSkipped() {
        var shop = fx.shop("provider", "master");
        tx.executeWithoutResult(_ -> publisher.publishEvent(
                new BookingCompleted(Ids.next(), Instant.now(), Ids.next(), shop.merchantId(), shop.ownerId(), 0)));
        // No exception escapes and no escrow row appears.
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(jdbc.sql("select count(*) from payments.escrows where merchant_id = ?")
                        .params(shop.merchantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
