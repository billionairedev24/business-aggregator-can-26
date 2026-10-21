package ca.northline.worker.notifications;

import ca.northline.email.EmailContent;
import ca.northline.email.EmailFormat;
import java.net.URI;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What an event means for a customer or a courier (S-102; docs/runbooks/push.md § Customer notifications):
 *
 * <table>
 *   <tr><th>event</th><th>to</th><th>Account › Notifications row</th><th>deep link</th></tr>
 *   <tr><td>order.packed (whole order ready)</td><td>customer</td><td>order_updates</td><td>/app/orders/{id}</td></tr>
 *   <tr><td>delivery.picked_up</td><td>customer</td><td>order_updates</td><td>/app/orders/{id}, /app/food/orders/{id}</td></tr>
 *   <tr><td>order.delivered</td><td>customer</td><td>order_updates</td><td>same</td></tr>
 *   <tr><td>S-151: order.cancelled (whole or one shop's part, or refunded after a failed delivery),
 *       order.delivery_failed</td><td>customer</td><td>order_updates</td><td>/app/orders/{id}</td></tr>
 *   <tr><td>S-151: return.updated (approved, declined, collected, refunded)</td><td>customer</td>
 *       <td>refunds_cases</td><td>/app/orders/{order id}</td></tr>
 *   <tr><td>booking.confirmed, the evening-before reminder, booking.en_route</td><td>customer</td>
 *       <td>booking_reminders</td><td>/app/bookings/{id}</td></tr>
 *   <tr><td>booking.completed</td><td>customer</td><td>sign_off</td><td>/app/bookings/{id}</td></tr>
 *   <tr><td>quote.sent</td><td>customer</td><td>quotes_messages</td><td>/app/quotes/{id}</td></tr>
 *   <tr><td>refund.case_updated, refund.issued</td><td>customer</td><td>refunds_cases</td><td>/app/cases/{caseNumber}</td></tr>
 *   <tr><td>points.credited (S-152: a completed sale, a referral)</td><td>customer</td><td>order_updates</td>
 *       <td>/app/account/points</td></tr>
 *   <tr><td>S-162: plus_payment.updated (paid — the receipt —, action_required, declined, lapsed, refunded)</td>
 *       <td>the payer</td><td>— (always: a payment they made; email, and push but for the receipt)</td>
 *       <td>/app/wallet</td></tr>
 *   <tr><td>delivery.assigned, run.changed</td><td>courier</td><td>— (always, push only)</td><td>/courier/run</td></tr>
 *   <tr><td>{@value #OFFER} (S-108; no producer yet)</td><td>customer</td><td>offers — commercial: the person's
 *       consent per channel, checked at send time</td><td>the offer's page</td></tr>
 * </table>
 *
 * Customers get push, SMS and email as their matrix says (quiet hours hold push and SMS). The words are one line in
 * the reader's language with ids, amounts, dates and the business's name only — never the customer's own details.
 */
public final class PersonalNotices {

    /** The evening-before reminder is not a domain event: the worker's job makes it (type, payload: the booking). */
    public static final String REMINDER = "booking.booking_reminder";

    /**
     * S-108: Northline's own offer to one customer — a commercial message, not a domain event: whatever sends offers
     * hands the worker this type and {@link #offer} as the payload (kept by a deferred row and re-read at send time).
     */
    public static final String OFFER = "messaging.offer";

    private static final Set<Channel> ALL = EnumSet.allOf(Channel.class);
    private static final Set<Channel> PUSH = EnumSet.of(Channel.PUSH);

    private final Subjects subjects;

    public PersonalNotices(Subjects subjects) {
        this.subjects = subjects;
    }

    /** The customer's or courier's notices for an event (usually one, none when it isn't for them). */
    public List<Notice> of(String eventId, String type, int version, JsonNode data) {
        if (version != 1) {
            return List.of(); // a new version needs its own wording here first (the schema changed)
        }
        var id = data.path("aggregateId").asString("");
        return Optional.ofNullable(
                        switch (type) {
                            case "orders.order_packed" ->
                                "ready".equals(data.path("orderState").asString(""))
                                        ? order(eventId, type, data, id, "order.packed")
                                        : null;
                            case "fulfilment.delivery_picked_up" ->
                                order(eventId, type, data, id, "order.out_for_delivery");
                            case "orders.order_delivered" -> order(eventId, type, data, id, "order.delivered");
                            case "orders.order_cancelled" -> cancelled(eventId, type, data, id);
                            case "orders.order_delivery_failed" -> deliveryFailed(eventId, type, data, id);
                            case "orders.return_updated" -> returned(eventId, type, data, id);
                            case "booking.booking_confirmed" -> booking(eventId, type, data, id, "booking.confirmed");
                            case REMINDER -> booking(eventId, type, data, id, "booking.reminder");
                            case "booking.booking_en_route" -> booking(eventId, type, data, id, "booking.en_route");
                            case "booking.booking_completed" -> booking(eventId, type, data, id, "booking.completed");
                            case "booking.quote_sent" -> quote(eventId, type, data, id);
                            case "messaging.message_sent" -> message(eventId, type, data, id);
                            case "booking.booking_balance_held" ->
                                bookingMoney(eventId, type, data, id, "booking.balance_held", "amountCents");
                            case "booking.booking_balance_failed" ->
                                bookingMoney(
                                        eventId,
                                        type,
                                        data,
                                        id,
                                        data.path("retryAt").isString()
                                                ? "booking.balance_failed"
                                                : "booking.balance_failed_final",
                                        "amountCents");
                            case "booking.booking_cancelled" ->
                                bookingMoney(
                                        eventId,
                                        type,
                                        data,
                                        id,
                                        data.path("feeCents").asLong() > 0
                                                ? "booking.cancelled_fee"
                                                : "booking.cancelled",
                                        "feeCents");
                            case "payments.refund_case_updated", "payments.refund_issued" ->
                                refund(eventId, type, data, id);
                            case OFFER -> offer(eventId, type, data);
                            case "loyalty.points_credited" -> points(eventId, type, data);
                            case "payments.plus_payment_updated" -> plus(eventId, type, data);
                            case "fulfilment.delivery_assigned" -> courier(eventId, type, data, id, "run.assigned");
                            case "fulfilment.run_changed" ->
                                courier(
                                        eventId,
                                        type,
                                        data,
                                        id,
                                        "run." + data.path("change").asString("changed"));
                            default -> null;
                        })
                .map(List::of)
                .orElse(List.of());
    }

    private @Nullable Notice order(String eventId, String type, JsonNode data, String orderId, String key) {
        var order = subjects.order(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        var path = ("food".equals(order.type()) ? "/app/food/orders/" : "/app/orders/") + orderId;
        return customer(
                eventId,
                type,
                data,
                order.customerId(),
                text(data, "merchantId"),
                "order_updates",
                key,
                (_, _) -> new Object[] {},
                new Notice.Push(path, Map.of("type", key, "orderId", orderId), "order:" + orderId));
    }

    /** S-151: the whole order, one shop's part of it, or a failed delivery refunded. */
    private @Nullable Notice cancelled(String eventId, String type, JsonNode data, String orderId) {
        var order = subjects.order(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        var whole = data.path("whole").asBoolean(true);
        var key = !whole
                ? "order.part_cancelled"
                : "refunded".equals(data.path("state").asString("")) ? "order.refunded" : "order.cancelled";
        var shops = data.path("merchantIds");
        var merchant =
                !whole && shops.isArray() && !shops.isEmpty() ? shops.get(0).asString() : null;
        return customer(
                eventId,
                type,
                data,
                order.customerId(),
                merchant,
                "order_updates",
                key,
                (_, business) -> new Object[] {business},
                new Notice.Push("/app/orders/" + orderId, Map.of("type", key, "orderId", orderId), "order:" + orderId));
    }

    /** S-151: the courier couldn't hand it over — choose re-delivery or a refund by {@code decideBy}. */
    private @Nullable Notice deliveryFailed(String eventId, String type, JsonNode data, String orderId) {
        var order = subjects.order(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        var by = data.path("decideBy").isString()
                ? OffsetDateTime.parse(data.path("decideBy").asString()).toInstant()
                : null;
        var key = "order.delivery_failed";
        return customer(
                eventId,
                type,
                data,
                order.customerId(),
                null,
                "order_updates",
                key,
                (f, business) -> new Object[] {business, by == null ? "" : f.dateTime(by)},
                new Notice.Push("/app/orders/" + orderId, Map.of("type", key, "orderId", orderId), "order:" + orderId));
    }

    /** S-151: a return the shop approved or declined, the courier collected, or that was refunded. */
    private @Nullable Notice returned(String eventId, String type, JsonNode data, String returnId) {
        var state = data.path("state").asString("");
        var key = switch (state) {
            case "approved" ->
                "pickup".equals(data.path("method").asString(""))
                        ? "return.approved_pickup"
                        : "return.approved_dropoff";
            case "declined", "collected", "refunded" -> "return." + state;
            default -> null;
        };
        var customer = key == null ? null : subjects.returnCustomer(returnId).orElse(null);
        if (key == null || customer == null) {
            return null;
        }
        var ref = data.path("returnRef").asString("");
        var amount = data.path("amountCents").asLong();
        var orderId = data.path("orderId").asString("");
        return customer(
                eventId,
                type,
                data,
                customer,
                text(data, "merchantId"),
                "refunds_cases",
                key,
                (f, business) -> new Object[] {business, ref, f.money(amount)},
                new Notice.Push(
                        "/app/orders/" + orderId, Map.of("type", key, "orderId", orderId), "return:" + returnId));
    }

    private @Nullable Notice booking(String eventId, String type, JsonNode data, String bookingId, String key) {
        var booking = subjects.booking(bookingId).orElse(null);
        if (booking == null) {
            return null;
        }
        var startsAt = booking.startsAt() != null
                ? booking.startsAt()
                : data.path("startsAt").isString()
                        ? OffsetDateTime.parse(data.path("startsAt").asString()).toInstant()
                        : null;
        var row = key.equals("booking.completed") ? "sign_off" : "booking_reminders";
        var merchant = booking.merchantId() != null ? booking.merchantId() : text(data, "merchantId");
        return customer(
                eventId,
                type,
                data,
                booking.customerId(),
                merchant,
                row,
                key,
                (f, business) -> new Object[] {business, startsAt == null ? "" : f.dateTime(startsAt)},
                new Notice.Push(
                        "/app/bookings/" + bookingId,
                        Map.of("type", key, "bookingId", bookingId),
                        "booking:" + bookingId));
    }

    private @Nullable Notice quote(String eventId, String type, JsonNode data, String quoteId) {
        var customer =
                subjects.quoteCustomer(data.path("requestId").asString("")).orElse(null);
        if (customer == null) {
            return null;
        }
        var key = data.path("quoteVersion").asInt(1) > 1 ? "quote.revised" : "quote.sent";
        var total = data.path("totalCents").asLong();
        var validUntil = data.path("validUntil").isString()
                ? OffsetDateTime.parse(data.path("validUntil").asString()).toInstant()
                : null;
        return customer(
                eventId,
                type,
                data,
                customer,
                text(data, "merchantId"),
                "quotes_messages",
                key,
                (f, business) -> new Object[] {business, f.money(total), validUntil == null ? "" : f.date(validUntil)},
                new Notice.Push("/app/quotes/" + quoteId, Map.of("type", key, "quoteId", quoteId), "quote:" + quoteId));
    }

    /** S-153: the business (or Northline) wrote in one of the customer's threads; never the text itself. */
    private @Nullable Notice message(String eventId, String type, JsonNode data, String threadId) {
        if (!"customer".equals(data.path("threadKind").asString(""))
                || "customer".equals(data.path("senderRole").asString(""))) {
            return null;
        }
        var customer = subjects.threadCustomer(threadId).orElse(null);
        if (customer == null) {
            return null;
        }
        return customer(
                eventId,
                type,
                data,
                customer,
                text(data, "merchantId"),
                "quotes_messages",
                "message.received",
                (_, business) -> new Object[] {business},
                new Notice.Push(
                        "/app/messages/" + threadId,
                        Map.of("type", "message.received", "threadId", threadId),
                        "thread:" + threadId));
    }

    /**
     * S-153: a deposit quote's balance held or refused, a cancellation confirmed — {0} the business, {1} the start,
     * {2} the amount (with its tax).
     */
    private @Nullable Notice bookingMoney(
            String eventId, String type, JsonNode data, String bookingId, String key, String amountField) {
        var booking = subjects.booking(bookingId).orElse(null);
        if (booking == null) {
            return null;
        }
        var startsAt = data.path("startsAt").isString()
                ? OffsetDateTime.parse(data.path("startsAt").asString()).toInstant()
                : booking.startsAt();
        var amount = data.path(amountField).asLong()
                + data.path("amountCents".equals(amountField) ? "taxCents" : "feeTaxCents")
                        .asLong();
        return customer(
                eventId,
                type,
                data,
                booking.customerId(),
                text(data, "merchantId"),
                "booking_reminders",
                key,
                (f, business) -> new Object[] {business, startsAt == null ? "" : f.dateTime(startsAt), f.money(amount)},
                new Notice.Push(
                        "/app/bookings/" + bookingId,
                        Map.of("type", key, "bookingId", bookingId),
                        "booking:" + bookingId));
    }

    private @Nullable Notice refund(String eventId, String type, JsonNode data, String refundId) {
        var customer = subjects.refundCustomer(refundId).orElse(null);
        var caseNumber = data.path("caseNumber").asString("");
        if (customer == null || caseNumber.isEmpty()) {
            return null;
        }
        var key = type.equals("payments.refund_issued")
                ? "refund.issued"
                : "refund." + data.path("change").asString("updated");
        var amount = data.path("amountCents").asLong();
        var respondBy = data.path("respondBy").isString()
                ? OffsetDateTime.parse(data.path("respondBy").asString()).toInstant()
                : null;
        return customer(
                eventId,
                type,
                data,
                customer,
                text(data, "merchantId"),
                "refunds_cases",
                key,
                (f, business) -> new Object[] {
                    caseNumber, business, f.money(amount), respondBy == null ? "" : f.dateTime(respondBy)
                },
                new Notice.Push(
                        "/app/cases/" + caseNumber,
                        Map.of("type", key, "caseNumber", caseNumber),
                        "case:" + caseNumber));
    }

    /** S-152: points a completed sale or a referral credited. */
    private static @Nullable Notice points(String eventId, String type, JsonNode data) {
        var customer = data.path("customerId").asString("");
        var points = data.path("points").asLong();
        if (customer.isEmpty() || points <= 0) {
            return null;
        }
        var referral = "referral".equals(data.path("reason").asString(""));
        var key = referral ? "points.referral" : "points.earned";
        return customer(
                eventId,
                type,
                data,
                customer,
                referral ? null : text(data, "merchantId"),
                "order_updates",
                key,
                (_, business) -> new Object[] {points, business},
                new Notice.Push("/app/account/points", Map.of("type", key), "points:" + eventId));
    }

    /**
     * S-162: a Northline Plus payment — Northline bills Plus itself. Not governed by the matrix (a payment the person
     * made: the receipt, or what they must do for it), so not held by quiet hours either; email always (the
     * {@link EmailContent.PlusPayment} receipt or notice), push too except for the receipt, no SMS.
     */
    private static @Nullable Notice plus(String eventId, String type, JsonNode data) {
        var customer = data.path("customerId").asString("");
        var outcome = data.path("outcome").asString("");
        if (customer.isEmpty() || !EmailContent.PlusPayment.OUTCOMES.contains(outcome)) {
            return null;
        }
        var plan = "annual".equals(data.path("plan").asString("")) ? "annual" : "monthly";
        var amount = data.path("amountCents").asLong();
        var tax = data.path("taxCents").asLong();
        var from = OffsetDateTime.parse(data.path("periodStart").asString()).toInstant();
        var to = OffsetDateTime.parse(data.path("periodEnd").asString()).toInstant();
        var retryAt = data.path("retryAt").isString()
                ? OffsetDateTime.parse(data.path("retryAt").asString()).toInstant()
                : null;
        var key = "plus." + outcome;
        Arguments arguments = (f, _) -> new Object[] {f.money(amount + tax), f.date(from), f.date(to), retryAt == null ? "" : f.date(retryAt)};
        var words = texts(key, false, arguments);
        return new Notice(
                eventId,
                type,
                1,
                null,
                new Notice.Audience.Customer(customer),
                null,
                "paid".equals(outcome) ? EnumSet.of(Channel.EMAIL) : EnumSet.of(Channel.EMAIL, Channel.PUSH),
                data,
                new Notice.Texts() {
                    @Override
                    public String text(String business, Locale locale, ZoneId zone) {
                        return words.text(business, locale, zone);
                    }

                    @Override
                    public String title(String business, Locale locale, ZoneId zone) {
                        return words.title(business, locale, zone);
                    }

                    @Override
                    public EmailContent email(String business, URI link, Locale locale, ZoneId zone) {
                        return new EmailContent.PlusPayment(outcome, plan, amount, tax, from, to, retryAt, link);
                    }
                },
                new Notice.Push("/app/wallet", Map.of("type", key), "plus:" + data.path("aggregateId").asString("")));
    }

    private static @Nullable Notice offer(String eventId, String type, JsonNode data) {
        var customer = data.path("customerId").asString("");
        var path = data.path("path").asString("");
        if (customer.isEmpty() || !path.startsWith("/")) {
            return null;
        }
        var titles = new String[] {
            data.path("titleEn").asString(""), data.path("titleFr").asString("")
        };
        var bodies = new String[] {
            data.path("bodyEn").asString(""), data.path("bodyFr").asString("")
        };
        return new Notice(
                eventId,
                type,
                1,
                null,
                new Notice.Audience.Customer(customer),
                "offers",
                ALL,
                data,
                new Notice.Texts() {
                    @Override
                    public String text(String business, Locale locale, ZoneId zone) {
                        return bodies[french(locale) ? 1 : 0];
                    }

                    @Override
                    public String title(String business, Locale locale, ZoneId zone) {
                        return titles[french(locale) ? 1 : 0];
                    }

                    @Override
                    public String sms(String business, Locale locale, ZoneId zone) {
                        return (french(locale) ? "Northline : " : "Northline: ") + title(business, locale, zone) + ". "
                                + text(business, locale, zone);
                    }

                    @Override
                    public EmailContent email(String business, URI link, Locale locale, ZoneId zone) {
                        return new EmailContent.MarketingOffer(
                                title(business, locale, zone), text(business, locale, zone), link);
                    }
                },
                new Notice.Push(path, Map.of("type", "offer"), "offer:" + eventId));
    }

    private @Nullable Notice courier(String eventId, String type, JsonNode data, String runId, String key) {
        var user = subjects.courierUser(data.path("courierId").asString("")).orElse(null);
        if (user == null) {
            return null;
        }
        var stops = data.path("orderIds").size();
        return new Notice(
                eventId,
                type,
                1,
                null,
                new Notice.Audience.Courier(user),
                null,
                PUSH,
                data,
                texts(key, false, (_, _) -> new Object[] {stops}),
                new Notice.Push("/courier/run", Map.of("type", key, "runId", runId), "run:" + runId));
    }

    private static Notice customer(
            String eventId,
            String type,
            JsonNode data,
            String customerId,
            @Nullable String merchantId,
            String row,
            String key,
            Arguments arguments,
            Notice.Push push) {
        return new Notice(
                eventId,
                type,
                1,
                merchantId,
                new Notice.Audience.Customer(customerId),
                row,
                ALL,
                data,
                texts(key, true, arguments),
                push);
    }

    private static @Nullable String text(JsonNode data, String field) {
        return data.path(field).isString() ? data.path(field).asString() : null;
    }

    @FunctionalInterface
    private interface Arguments {
        Object[] of(EmailFormat format, String business);
    }

    private static Notice.Texts texts(String key, boolean customer, Arguments arguments) {
        return new Notice.Texts() {
            @Override
            public String text(String business, Locale locale, ZoneId zone) {
                return format(key, locale, arguments.of(EmailFormat.of(locale, zone), business));
            }

            @Override
            public String title(String business, Locale locale, ZoneId zone) {
                return format(key + ".title", locale, arguments.of(EmailFormat.of(locale, zone), business));
            }

            @Override
            public String sms(String business, Locale locale, ZoneId zone) {
                return (french(locale) ? "Northline : " : "Northline: ") + text(business, locale, zone);
            }

            @Override
            public @Nullable EmailContent email(String business, URI link, Locale locale, ZoneId zone) {
                return customer
                        ? new EmailContent.CustomerUpdate(
                                business, title(business, locale, zone), text(business, locale, zone), link)
                        : null;
            }
        };
    }

    private static boolean french(Locale locale) {
        return "fr".equals(locale.getLanguage());
    }

    static String format(String key, Locale locale, Object... args) {
        var pattern = (french(locale) ? FR : EN).get(key);
        if (pattern == null) {
            throw new IllegalArgumentException("No notification text " + key);
        }
        return new MessageFormat(pattern, locale).format(args);
    }

    /** The reminder's payload: the booking id, as the job finds it (the deferred row keeps it). */
    public static Map<String, Object> reminder(String bookingId, Instant at) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("aggregateId", bookingId);
        payload.put("occurredAt", at.toString());
        return payload;
    }

    /** An offer's payload (S-108): who it is for, its words in both languages and the page it opens. */
    public static Map<String, Object> offer(
            String customerId, String titleEn, String bodyEn, String titleFr, String bodyFr, String path) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("aggregateId", customerId);
        payload.put("customerId", customerId);
        payload.put("titleEn", titleEn);
        payload.put("bodyEn", bodyEn);
        payload.put("titleFr", titleFr);
        payload.put("bodyFr", bodyFr);
        payload.put("path", path);
        return payload;
    }

    // Order texts: no number (the app shows the order); booking {0} = business, {1} = start; quote {0} business,
    // {1} total, {2} valid until; refund {0} case, {1} business, {2} amount, {3} respond by; run {0} = orders.
    // MessageFormat: '' is an apostrophe (’ is used instead). A sentence ending on a time has no period ("a.m.").
    static final Map<String, String> EN = Map.ofEntries(
            Map.entry("order.packed", "Your order is packed and ready to ship. We’ll tell you when it’s on its way."),
            Map.entry("order.packed.title", "Order packed"),
            Map.entry("order.out_for_delivery", "Your order is out for delivery. Follow the courier live in the app."),
            Map.entry("order.out_for_delivery.title", "Out for delivery"),
            Map.entry("order.delivered", "Your order was delivered. Something not right? Report it from the order."),
            Map.entry("order.delivered.title", "Order delivered"),
            Map.entry("order.cancelled", "Your order was cancelled. Anything charged goes back to your card."),
            Map.entry("order.cancelled.title", "Order cancelled"),
            Map.entry(
                    "order.part_cancelled",
                    "{0} cancelled its part of your order. That part goes back to your card; the rest is still coming."),
            Map.entry("order.part_cancelled.title", "Part of your order was cancelled"),
            Map.entry(
                    "order.refunded", "Your order was refunded after the missed delivery. It goes back to your card."),
            Map.entry("order.refunded.title", "Order refunded"),
            Map.entry(
                    "order.delivery_failed",
                    "The courier couldn’t hand your order over. Choose re-delivery or a refund by {1}"),
            Map.entry("order.delivery_failed.title", "Delivery missed"),
            Map.entry("return.approved_pickup", "{0} approved return {1}. A courier will collect it."),
            Map.entry("return.approved_dropoff", "{0} approved return {1}. Bring it to the shop for your refund."),
            Map.entry("return.declined", "{0} declined return {1}. You can ask a Northline agent to look at it."),
            Map.entry("return.collected", "The courier collected return {1}."),
            Map.entry("return.refunded", "Return {1} is refunded: {2} plus tax goes back to your card."),
            Map.entry("return.approved_pickup.title", "Return {1} approved"),
            Map.entry("return.approved_dropoff.title", "Return {1} approved"),
            Map.entry("return.declined.title", "Return {1} declined"),
            Map.entry("return.collected.title", "Return {1} collected"),
            Map.entry("return.refunded.title", "Return {1} refunded"),
            Map.entry("booking.confirmed", "{0} confirmed your booking for {1}"),
            Map.entry("booking.confirmed.title", "Booking confirmed"),
            Map.entry("booking.reminder", "Reminder: {0} is booked for {1}"),
            Map.entry("booking.reminder.title", "Your booking is tomorrow"),
            Map.entry("booking.en_route", "{0} is on the way. See the live arrival time in the app."),
            Map.entry("booking.en_route.title", "Your provider is on the way"),
            Map.entry("booking.completed", "{0} marked the job done. Check the photos and sign off in the app."),
            Map.entry("booking.completed.title", "Job done — please sign off"),
            Map.entry("message.received", "{0} sent you a message. Read and reply in the app."),
            Map.entry("message.received.title", "New message from {0}"),
            Map.entry("booking.balance_held", "The balance of {2} for {0} on {1} is now held in escrow."),
            Map.entry("booking.balance_held.title", "Balance held"),
            Map.entry(
                    "booking.balance_failed",
                    "We couldn’t hold the balance of {2} for {0} on {1} — pay it from the booking; we’ll try again."),
            Map.entry("booking.balance_failed.title", "Balance not held"),
            Map.entry(
                    "booking.balance_failed_final",
                    "We couldn’t hold the balance of {2} for {0} on {1} — pay it from the booking before the visit."),
            Map.entry("booking.balance_failed_final.title", "Pay your balance"),
            Map.entry(
                    "booking.cancelled",
                    "Your booking with {0} on {1} is cancelled. The hold on your card is released."),
            Map.entry("booking.cancelled.title", "Booking cancelled"),
            Map.entry(
                    "booking.cancelled_fee",
                    "Your booking with {0} on {1} is cancelled. A late-cancellation fee of {2} was charged; the rest"
                            + " of the hold is released."),
            Map.entry("booking.cancelled_fee.title", "Booking cancelled"),
            Map.entry("quote.sent", "{0} sent you a quote of {1}, valid until {2}."),
            Map.entry("quote.sent.title", "New quote from {0}"),
            Map.entry("quote.revised", "{0} revised their quote: {1}, valid until {2}."),
            Map.entry("quote.revised.title", "Revised quote from {0}"),
            Map.entry("refund.requested", "We sent refund request {0} to {1}. They have until {3} to answer."),
            Map.entry("refund.approved", "Refund {0} of {2} was approved."),
            Map.entry("refund.agent_review", "A Northline agent is reviewing refund request {0}."),
            Map.entry("refund.denied", "Refund request {0} was declined. See why in the case."),
            Map.entry("refund.issued", "Refund {0} of {2} is on its way to your card."),
            Map.entry("refund.requested.title", "Refund request {0}"),
            Map.entry("refund.approved.title", "Refund {0} approved"),
            Map.entry("refund.agent_review.title", "Refund request {0}"),
            Map.entry("refund.denied.title", "Refund request {0}"),
            Map.entry("refund.issued.title", "Refund {0} paid"),
            Map.entry("run.assigned", "You have a new run: {0,choice,1#1 order|1<{0} orders}. Open it to start."),
            Map.entry("run.assigned.title", "New run"),
            Map.entry("run.unassigned", "Dispatch gave your run to another courier. You’re free for the next one."),
            Map.entry("run.unassigned.title", "Run changed"),
            Map.entry("run.cancelled", "Your run was cancelled: every order on it was cancelled."),
            Map.entry("run.cancelled.title", "Run cancelled"),
            Map.entry("run.stops_removed", "An order on your run was cancelled. Its stops are gone from your run."),
            Map.entry("run.stops_removed.title", "Run changed"),
            Map.entry("run.stops_moved", "Dispatch moved the stops you hadn’t picked up yet to another courier."),
            Map.entry("run.stops_moved.title", "Run changed"),
            Map.entry("points.earned", "You earned {0,number,integer} points from {1}."),
            Map.entry("points.earned.title", "Points earned"),
            Map.entry("points.referral", "A neighbour’s first order earned you {0,number,integer} points."),
            Map.entry("points.referral.title", "Thanks for inviting a neighbour"),
            Map.entry("plus.paid", "Thanks! We charged {0} for Northline Plus ({1} – {2})."),
            Map.entry("plus.paid.title", "Northline Plus receipt"),
            Map.entry("plus.action_required", "Your bank wants you to confirm the Northline Plus payment of {0}."),
            Map.entry("plus.action_required.title", "Confirm your Plus payment"),
            Map.entry("plus.declined", "Your card was declined for Northline Plus ({0}). We’ll try again on {3}."),
            Map.entry("plus.declined.title", "Plus payment declined"),
            Map.entry("plus.lapsed", "We couldn’t collect {0} for Northline Plus, so your membership ended."),
            Map.entry("plus.lapsed.title", "Northline Plus ended"),
            Map.entry("plus.refunded", "{0} for Northline Plus is on its way back to your card."),
            Map.entry("plus.refunded.title", "Plus payment refunded"));

    static final Map<String, String> FR = Map.ofEntries(
            Map.entry(
                    "order.packed",
                    "Votre commande est emballée et prête à partir. Nous vous dirons quand elle sera en route."),
            Map.entry("order.packed.title", "Commande emballée"),
            Map.entry(
                    "order.out_for_delivery",
                    "Votre commande est en livraison. Suivez le livreur en direct dans l’appli."),
            Map.entry("order.out_for_delivery.title", "En livraison"),
            Map.entry("order.delivered", "Votre commande a été livrée. Un problème? Signalez-le depuis la commande."),
            Map.entry("order.delivered.title", "Commande livrée"),
            Map.entry(
                    "order.cancelled", "Votre commande a été annulée. Tout montant prélevé retourne sur votre carte."),
            Map.entry("order.cancelled.title", "Commande annulée"),
            Map.entry(
                    "order.part_cancelled",
                    "{0} a annulé sa partie de votre commande. Elle retourne sur votre carte; le reste arrive comme prévu."),
            Map.entry("order.part_cancelled.title", "Une partie de votre commande a été annulée"),
            Map.entry(
                    "order.refunded",
                    "Votre commande a été remboursée après la livraison manquée. Le montant retourne sur votre carte."),
            Map.entry("order.refunded.title", "Commande remboursée"),
            Map.entry(
                    "order.delivery_failed",
                    "Le livreur n’a pas pu vous remettre votre commande. Choisissez une nouvelle livraison ou un"
                            + " remboursement d’ici le {1}"),
            Map.entry("order.delivery_failed.title", "Livraison manquée"),
            Map.entry("return.approved_pickup", "{0} a accepté le retour {1}. Un livreur viendra le chercher."),
            Map.entry(
                    "return.approved_dropoff",
                    "{0} a accepté le retour {1}. Rapportez-le au commerce pour être remboursé."),
            Map.entry(
                    "return.declined",
                    "{0} a refusé le retour {1}. Vous pouvez demander à un agent Northline de l’examiner."),
            Map.entry("return.collected", "Le livreur a récupéré le retour {1}."),
            Map.entry("return.refunded", "Le retour {1} est remboursé : {2} plus taxes retournent sur votre carte."),
            Map.entry("return.approved_pickup.title", "Retour {1} accepté"),
            Map.entry("return.approved_dropoff.title", "Retour {1} accepté"),
            Map.entry("return.declined.title", "Retour {1} refusé"),
            Map.entry("return.collected.title", "Retour {1} récupéré"),
            Map.entry("return.refunded.title", "Retour {1} remboursé"),
            Map.entry("booking.confirmed", "{0} a confirmé votre réservation du {1}"),
            Map.entry("booking.confirmed.title", "Réservation confirmée"),
            Map.entry("booking.reminder", "Rappel : {0} est réservé pour le {1}"),
            Map.entry("booking.reminder.title", "Votre réservation est demain"),
            Map.entry("booking.en_route", "{0} est en route. Voyez l’heure d’arrivée en direct dans l’appli."),
            Map.entry("booking.en_route.title", "Votre prestataire est en route"),
            Map.entry("booking.completed", "{0} a terminé les travaux. Vérifiez les photos et approuvez dans l’appli."),
            Map.entry("booking.completed.title", "Travaux terminés — à approuver"),
            Map.entry("message.received", "{0} vous a écrit. Lisez et répondez dans l’appli."),
            Map.entry("message.received.title", "Nouveau message de {0}"),
            Map.entry("booking.balance_held", "Le solde de {2} pour {0} le {1} est maintenant retenu en fiducie."),
            Map.entry("booking.balance_held.title", "Solde retenu"),
            Map.entry(
                    "booking.balance_failed",
                    "Nous n’avons pas pu retenir le solde de {2} pour {0} le {1}. Payez-le depuis la réservation —"
                            + " nous réessaierons."),
            Map.entry("booking.balance_failed.title", "Solde non retenu"),
            Map.entry(
                    "booking.balance_failed_final",
                    "Nous n’avons pas pu retenir le solde de {2} pour {0} le {1}. Payez-le depuis la réservation"
                            + " avant la visite."),
            Map.entry("booking.balance_failed_final.title", "Payez votre solde"),
            Map.entry(
                    "booking.cancelled",
                    "Votre réservation avec {0} le {1} est annulée. Le montant retenu sur votre carte est libéré."),
            Map.entry("booking.cancelled.title", "Réservation annulée"),
            Map.entry(
                    "booking.cancelled_fee",
                    "Votre réservation avec {0} le {1} est annulée. Des frais d’annulation tardive de {2} ont été"
                            + " facturés; le reste du montant retenu est libéré."),
            Map.entry("booking.cancelled_fee.title", "Réservation annulée"),
            Map.entry("quote.sent", "{0} vous a envoyé un devis de {1}, valide jusqu’au {2}."),
            Map.entry("quote.sent.title", "Nouveau devis de {0}"),
            Map.entry("quote.revised", "{0} a révisé son devis : {1}, valide jusqu’au {2}."),
            Map.entry("quote.revised.title", "Devis révisé de {0}"),
            Map.entry(
                    "refund.requested",
                    "Nous avons transmis la demande de remboursement {0} à {1}. Réponse attendue d’ici le {3}."),
            Map.entry("refund.approved", "Le remboursement {0} de {2} a été approuvé."),
            Map.entry("refund.agent_review", "Un agent Northline examine la demande de remboursement {0}."),
            Map.entry(
                    "refund.denied", "La demande de remboursement {0} a été refusée. Voyez pourquoi dans le dossier."),
            Map.entry("refund.issued", "Le remboursement {0} de {2} est en route vers votre carte."),
            Map.entry("refund.requested.title", "Demande de remboursement {0}"),
            Map.entry("refund.approved.title", "Remboursement {0} approuvé"),
            Map.entry("refund.agent_review.title", "Demande de remboursement {0}"),
            Map.entry("refund.denied.title", "Demande de remboursement {0}"),
            Map.entry("refund.issued.title", "Remboursement {0} versé"),
            Map.entry(
                    "run.assigned",
                    "Nouvelle tournée : {0,choice,1#1 commande|1<{0} commandes}. Ouvrez-la pour commencer."),
            Map.entry("run.assigned.title", "Nouvelle tournée"),
            Map.entry(
                    "run.unassigned",
                    "La répartition a confié votre tournée à un autre livreur. Vous êtes libre pour la suivante."),
            Map.entry("run.unassigned.title", "Tournée modifiée"),
            Map.entry("run.cancelled", "Votre tournée est annulée : toutes ses commandes ont été annulées."),
            Map.entry("run.cancelled.title", "Tournée annulée"),
            Map.entry(
                    "run.stops_removed",
                    "Une commande de votre tournée a été annulée. Ses arrêts ont été retirés de votre tournée."),
            Map.entry("run.stops_removed.title", "Tournée modifiée"),
            Map.entry("run.stops_moved", "La répartition a confié les arrêts pas encore ramassés à un autre livreur."),
            Map.entry("run.stops_moved.title", "Tournée modifiée"),
            Map.entry("points.earned", "Vous avez gagné {0,number,integer} points chez {1}."),
            Map.entry("points.earned.title", "Points gagnés"),
            Map.entry("points.referral", "La première commande d’un voisin vous a rapporté {0,number,integer} points."),
            Map.entry("points.referral.title", "Merci d’avoir invité un voisin"),
            Map.entry("plus.paid", "Merci! Nous avons porté {0} pour Northline Plus ({1} – {2})."),
            Map.entry("plus.paid.title", "Reçu Northline Plus"),
            Map.entry("plus.action_required", "Votre banque vous demande de confirmer le paiement Northline Plus de {0}."),
            Map.entry("plus.action_required.title", "Confirmez votre paiement Plus"),
            Map.entry("plus.declined", "Votre carte a été refusée pour Northline Plus ({0}). Nous réessaierons le {3}."),
            Map.entry("plus.declined.title", "Paiement Plus refusé"),
            Map.entry("plus.lapsed", "Nous n’avons pas pu percevoir {0} pour Northline Plus : votre abonnement a pris fin."),
            Map.entry("plus.lapsed.title", "Northline Plus a pris fin"),
            Map.entry("plus.refunded", "{0} pour Northline Plus revient sur votre carte."),
            Map.entry("plus.refunded.title", "Paiement Plus remboursé"));
}
