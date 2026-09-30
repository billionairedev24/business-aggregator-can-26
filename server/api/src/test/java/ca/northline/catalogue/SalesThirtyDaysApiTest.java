package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.booking.api.BookingProgressed.BookingCompleted;
import ca.northline.catalogue.application.RecountSales;
import ca.northline.orders.api.OrderPacked;
import ca.northline.shared.DomainEvent;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-38: the Listings table's 30-day sales. Order and booking events trigger a recount: units on goods orders placed in
 * the last 30 days per offer, bookings made in the last 30 days per service. Cancelled orders, refunded lines and
 * orders, cancelled bookings and anything older than 30 days don't count. The nightly run lets old sales age out.
 */
class SalesThirtyDaysApiTest extends CatalogueApiTest {

    static final String LISTINGS = "/api/v1/merchants/{m}/listings";

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    RecountSales recountSales;

    @Test
    void orderEventRecountsUnitsSoldPerOffer() throws Exception {
        var seller = seller(MerchantRole.OWNER);
        var offer = product(seller, "Wiper blades", "S38-W");
        var other = product(seller, "Brake pads", "S38-B");
        var now = Instant.now();

        order(seller.merchantId(), offer, 3, "placed", "pending", now.minus(Duration.ofDays(2)));
        order(seller.merchantId(), offer, 2, "delivered", "packed", now.minus(Duration.ofDays(29)));
        order(seller.merchantId(), offer, 5, "cancelled", "pending", now.minus(Duration.ofDays(1)));
        order(seller.merchantId(), offer, 4, "refunded", "refunded", now.minus(Duration.ofDays(1)));
        order(seller.merchantId(), offer, 6, "delivered", "refunded", now.minus(Duration.ofDays(1)));
        order(seller.merchantId(), offer, 7, "delivered", "packed", now.minus(Duration.ofDays(31)));
        order(seller.merchantId(), other, 1, "packing", "packed", now.minus(Duration.ofHours(3)));
        // another seller's line for the same order shape doesn't count
        order(seller(MerchantRole.OWNER).merchantId(), offer, 9, "placed", "pending", now.minus(Duration.ofDays(1)));

        publish(new OrderPacked(Ids.next(), now, Ids.next(), seller.merchantId(), seller.userId(), 1, "packing"));

        await(() -> assertThat(sales(seller, offer)).isEqualTo(5));
        assertThat(sales(seller, other)).isEqualTo(1);
    }

    @Test
    void bookingEventRecountsBookingsPerService() throws Exception {
        var provider = provider(MerchantRole.OWNER);
        var service = service(provider, "S38 diagnostic");
        var now = Instant.now();

        booking(provider.merchantId(), service, "confirmed", now.minus(Duration.ofDays(3)));
        booking(provider.merchantId(), service, "completed", now.minus(Duration.ofDays(10)));
        booking(provider.merchantId(), service, "cancelled", now.minus(Duration.ofDays(1)));
        booking(provider.merchantId(), service, "signed_off", now.minus(Duration.ofDays(45)));

        publish(new BookingCompleted(Ids.next(), now, Ids.next(), provider.merchantId(), provider.userId(), 2));

        await(() -> assertThat(sales(provider, service)).isEqualTo(2));
    }

    @Test
    void nightlyRunLetsOldSalesAgeOut() throws Exception {
        var seller = seller(MerchantRole.OWNER);
        var offer = product(seller, "Old seller", "S38-O");
        jdbc.sql("update catalogue.offers set sales_30d = 12 where id = ?")
                .params(offer)
                .update();
        order(
                seller.merchantId(),
                offer,
                12,
                "delivered",
                "packed",
                Instant.now().minus(Duration.ofDays(40)));
        assertThat(sales(seller, offer)).isEqualTo(12);

        assertThat(recountSales.recountAll()).isPositive();

        assertThat(sales(seller, offer)).isZero();
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────────────────────────

    String product(Business biz, String title, String sku) throws Exception {
        return json(mvc.perform(postJson(
                                        "/api/v1/merchants/{m}/products",
                                        completeProduct(title, sku + "-" + Ids.next(), 1995),
                                        biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    String service(Business biz, String name) throws Exception {
        var body = """
                {"name":"%s","categoryId":"%s","pricingMode":"fixed","priceCents":12000,"durationMin":60,"bufferMin":20,
                 "included":"Scan and report","instantBook":true}
                """.formatted(name, MECHANIC);
        return json(mvc.perform(postJson("/api/v1/merchants/{m}/services", body, biz.merchantId())
                                .with(TestJwt.member(biz.userId())))
                        .andExpect(status().isCreated()))
                .get("id")
                .asString();
    }

    void order(String merchantId, String offerId, int qty, String orderState, String lineState, Instant placedAt) {
        var orderId = Ids.next();
        jdbc.sql("""
                        insert into orders.orders (id, customer_id, type, state, subtotal_cents, placed_at)
                        values (?, ?, 'goods', ?, ?, ?)
                        """)
                .params(orderId, Ids.next(), orderState, qty * 1995L, Timestamp.from(placedAt))
                .update();
        jdbc.sql("""
                        insert into orders.order_lines (id, order_id, merchant_id, offer_id, qty, unit_cents, state, title)
                        values (?, ?, ?, ?, ?, 1995, ?, 'Test line')
                        """)
                .params(Ids.next(), orderId, merchantId, offerId, qty, lineState)
                .update();
    }

    void booking(String merchantId, String serviceId, String state, Instant createdAt) {
        jdbc.sql("""
                        insert into booking.bookings (id, customer_id, merchant_id, service_id, type, state, starts_at,
                          ends_at, title, price_cents, created_at, updated_at)
                        values (?, ?, ?, ?, 'visit', ?, ?, ?, 'Diagnostic', 12000, ?, ?)
                        """)
                .params(
                        Ids.next(),
                        Ids.next(),
                        merchantId,
                        serviceId,
                        state,
                        Timestamp.from(createdAt.plus(Duration.ofDays(1))),
                        Timestamp.from(createdAt.plus(Duration.ofDays(1)).plus(Duration.ofHours(1))),
                        Timestamp.from(createdAt),
                        Timestamp.from(createdAt))
                .update();
    }

    void publish(DomainEvent event) {
        tx.executeWithoutResult(_ -> publisher.publishEvent(event));
    }

    int sales(Business biz, String listingId) throws Exception {
        var items = json(mvc.perform(get(LISTINGS, biz.merchantId()).with(TestJwt.member(biz.userId())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items").isArray()))
                .get("items");
        for (var item : items) {
            if (item.get("id").asString().equals(listingId)) {
                return item.get("sales30d").asInt();
            }
        }
        throw new AssertionError("listing " + listingId + " not in the table");
    }
}
