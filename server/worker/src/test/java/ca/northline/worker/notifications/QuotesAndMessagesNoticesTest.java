package ca.northline.worker.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.email.EmailContent;
import ca.northline.worker.events.EventSchemas;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-153: {@code quote.requested} tells every provider asked (row {@code quote_request}); a customer's message tells
 * the team (row {@code customer_message}), the business's message tells the customer (row {@code quotes_messages},
 * deep link to the thread); a balance that couldn't be held tells both; a cancellation tells the team. Words carry
 * references, amounts and times — never the message or the customer's details. The payloads are the api's schemas.
 */
class QuotesAndMessagesNoticesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final EventSchemas SCHEMAS = EventSchemas.fromClasspath(JSON, EventSchemas.LOCATION);
    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final Locale EN = Locale.CANADA;
    static final Locale FR = Locale.CANADA_FRENCH;

    final Subjects subjects = new Subjects() {
        @Override
        public Optional<Order> order(String orderId) {
            return Optional.empty();
        }

        @Override
        public Optional<Booking> booking(String bookingId) {
            return Optional.of(new Booking("u_customer", "m_1", Instant.parse("2026-10-21T16:00:00Z")));
        }

        @Override
        public Optional<String> quoteCustomer(String requestId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> refundCustomer(String refundId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> threadCustomer(String threadId) {
            return Optional.of("u_customer");
        }

        @Override
        public Optional<String> courierUser(String courierId) {
            return Optional.empty();
        }

        @Override
        public List<String> confirmedBookingsStarting(Instant from, Instant to) {
            return List.of();
        }
    };

    final PersonalNotices personal = new PersonalNotices(subjects);

    static JsonNode event(String type, String json) {
        var data = JSON.readTree(json);
        assertThat(SCHEMAS.validate(type, 1, data))
                .as(type + " matches its schema")
                .hasValue(List.of());
        return data;
    }

    /** Dates are in the locale's own form, with a narrow no-break space before "a.m.". */
    static String plain(String text) {
        return text.replace('\u202f', ' ');
    }

    List<Notice> notices(String type, JsonNode data) {
        var all = new java.util.ArrayList<Notice>(TeamNotices.of("01J9ZD3V000000000000000EV1", type, 1, data));
        all.addAll(personal.of("01J9ZD3V000000000000000EV1", type, 1, data));
        return all;
    }

    @Test
    void aQuoteRequestTellsEveryProviderAsked() {
        var data = event("booking.quote_requested", """
                {"eventId":"01J9ZD3V000000000000000EV1","occurredAt":"2026-10-20T15:00:00Z","aggregateId":"qr_1",
                 "quoteRef":"QT-3104","merchantIds":["m_1","m_2"],"categoryId":"service.automotive.mobile-mechanic",
                 "respondBy":"2026-10-20T17:00:00Z","expiresAt":"2026-10-27T15:00:00Z","photoCount":2}""");
        var out = notices("booking.quote_requested", data);

        assertThat(out).extracting(Notice::merchantId).containsExactly("m_1", "m_2");
        assertThat(out).allSatisfy(n -> {
            assertThat(n.row()).isEqualTo("quote_request");
            assertThat(n.audience()).isEqualTo(new Notice.Audience.Team(n.merchantId(), TeamNotices.OPERATORS));
            assertThat(n.channels()).containsExactlyInAnyOrder(Channel.EMAIL, Channel.SMS, Channel.PUSH);
        });
        var texts = out.getFirst().texts();
        assertThat(plain(texts.text("Prairie Wrench", EN, ZONE)))
                .isEqualTo(
                        "New quote request QT-3104. Answer by Tuesday, October 20, 2026 at 11:00 a.m. in the Studio.");
        assertThat(texts.sms("Prairie Wrench", FR, ZONE)).startsWith("Northline : Nouvelle demande de devis QT-3104.");
        assertThat(texts.email("Prairie Wrench", URI.create("http://localhost:3100/b/m_1/appointments"), EN, ZONE))
                .isInstanceOf(EmailContent.TeamUpdate.class);
        assertThat(texts.studioPage()).isEqualTo("appointments");
        assertThat(TeamNotices.DEFERRED).contains("booking.quote_requested");
    }

    @Test
    void aCustomersMessageTellsTheTeam_theBusinessesMessageTellsTheCustomer() {
        var fromCustomer = event("messaging.message_sent", """
                {"eventId":"01J9ZD3V000000000000000EV1","occurredAt":"2026-10-20T15:00:00Z","aggregateId":"th_1",
                 "messageId":"ms_1","merchantId":"m_1","threadKind":"customer","senderRole":"customer",
                 "senderId":"u_customer","flagged":false}""");
        var team = notices("messaging.message_sent", fromCustomer);
        assertThat(team).singleElement().satisfies(n -> {
            assertThat(n.row()).isEqualTo("customer_message");
            assertThat(n.audience()).isEqualTo(new Notice.Audience.Team("m_1", TeamNotices.OWNERS));
            assertThat(n.texts().studioPage()).isEqualTo("messages?thread=th_1");
            assertThat(plain(n.texts().text("Prairie Wrench", EN, ZONE)))
                    .isEqualTo("A customer wrote to Prairie Wrench. Reply in Messages.");
        });

        var fromBusiness = event("messaging.message_sent", """
                {"eventId":"01J9ZD3V000000000000000EV2","occurredAt":"2026-10-20T15:00:00Z","aggregateId":"th_1",
                 "messageId":"ms_2","merchantId":"m_1","threadKind":"customer","senderRole":"merchant",
                 "senderId":"u_owner","flagged":false}""");
        var customer = notices("messaging.message_sent", fromBusiness);
        assertThat(customer).singleElement().satisfies(n -> {
            assertThat(n.audience()).isEqualTo(new Notice.Audience.Customer("u_customer"));
            assertThat(n.row()).isEqualTo("quotes_messages");
            assertThat(n.push().path()).isEqualTo("/app/messages/th_1");
            assertThat(n.push().data()).containsOnlyKeys("type", "threadId");
            assertThat(n.texts().title("Prairie Wrench", FR, ZONE)).isEqualTo("Nouveau message de Prairie Wrench");
        });

        var support = event("messaging.message_sent", """
                {"eventId":"01J9ZD3V000000000000000EV3","occurredAt":"2026-10-20T15:00:00Z","aggregateId":"th_2",
                 "messageId":"ms_3","merchantId":"m_1","threadKind":"support","senderRole":"agent",
                 "senderId":"u_agent","flagged":false}""");
        assertThat(notices("messaging.message_sent", support)).isEmpty();
    }

    @Test
    void aBalanceThatCouldntBeHeld_tellsTheCustomerAndTheOwners_untilTheLastAttempt() {
        var retrying = event("booking.booking_balance_failed", """
                {"eventId":"01J9ZD3V000000000000000EV1","occurredAt":"2026-10-20T15:00:00Z","aggregateId":"bk_1",
                 "merchantId":"m_1","customerId":"u_customer","amountCents":32625,"taxCents":1631,
                 "startsAt":"2026-10-22T16:00:00Z","attempt":1,"reason":"declined","retryAt":"2026-10-20T21:00:00Z"}""");
        var out = notices("booking.booking_balance_failed", retrying);
        assertThat(out).hasSize(2);
        assertThat(plain(out.get(0).texts().text("Prairie Wrench", EN, ZONE)))
                .isEqualTo(
                        "The balance of $342.56 for the job of Thursday, October 22, 2026 at 10:00 a.m. couldn’t be held yet."
                                + " We’ll try again and asked the customer to pay it.");
        assertThat(out.get(1).audience()).isEqualTo(new Notice.Audience.Customer("u_customer"));
        assertThat(plain(out.get(1).texts().text("Prairie Wrench", EN, ZONE)))
                .isEqualTo(
                        "We couldn’t hold the balance of $342.56 for Prairie Wrench on Thursday, October 22, 2026 at 10:00 a.m."
                                + " — pay it from the booking; we’ll try again.");
        assertThat(out.get(1).push().path()).isEqualTo("/app/bookings/bk_1");

        var last = event("booking.booking_balance_failed", """
                {"eventId":"01J9ZD3V000000000000000EV2","occurredAt":"2026-10-20T15:00:00Z","aggregateId":"bk_1",
                 "merchantId":"m_1","customerId":"u_customer","amountCents":32625,"taxCents":1631,
                 "startsAt":"2026-10-22T16:00:00Z","attempt":3,"reason":"declined","retryAt":null}""");
        assertThat(notices("booking.booking_balance_failed", last)
                        .getFirst()
                        .texts()
                        .title("Prairie Wrench", EN, ZONE))
                .isEqualTo("Balance not held");
    }

    @Test
    void aLateCancellationTellsTheTeamAboutTheFee() {
        var data = event("booking.booking_cancelled", """
                {"eventId":"01J9ZD3V000000000000000EV1","occurredAt":"2026-10-20T15:00:00Z","aggregateId":"bk_1",
                 "merchantId":"m_1","customerId":"u_customer","memberUserId":"u_tech",
                 "startsAt":"2026-10-21T16:00:00Z","late":true,"feeCents":4450,"feeTaxCents":223}""");
        var out = notices("booking.booking_cancelled", data);
        assertThat(out).extracting(Notice::row).containsExactly("new_booking", "booking_reminders");
        assertThat(plain(out.get(0).texts().text("Prairie Wrench", EN, ZONE)))
                .isEqualTo(
                        "A customer cancelled the booking of Wednesday, October 21, 2026 at 10:00 a.m. late. The $46.73"
                                + " cancellation fee is held for you.");
        assertThat(out.get(1).texts().text("Prairie Wrench", FR, ZONE)).contains("46,73");
        assertThat(out)
                .allSatisfy(n -> assertThat(plain(n.texts().text("Prairie Wrench", EN, ZONE)))
                        .doesNotContain("u_customer"));
    }
}
