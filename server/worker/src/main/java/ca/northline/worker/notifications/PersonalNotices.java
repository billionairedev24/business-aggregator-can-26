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
 *   <tr><td>booking.confirmed, the evening-before reminder, booking.en_route</td><td>customer</td>
 *       <td>booking_reminders</td><td>/app/bookings/{id}</td></tr>
 *   <tr><td>booking.completed</td><td>customer</td><td>sign_off</td><td>/app/bookings/{id}</td></tr>
 *   <tr><td>quote.sent</td><td>customer</td><td>quotes_messages</td><td>/app/quotes/{id}</td></tr>
 *   <tr><td>refund.case_updated, refund.issued</td><td>customer</td><td>refunds_cases</td><td>/app/cases/{caseNumber}</td></tr>
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
                            case "booking.booking_confirmed" -> booking(eventId, type, data, id, "booking.confirmed");
                            case REMINDER -> booking(eventId, type, data, id, "booking.reminder");
                            case "booking.booking_en_route" -> booking(eventId, type, data, id, "booking.en_route");
                            case "booking.booking_completed" -> booking(eventId, type, data, id, "booking.completed");
                            case "booking.quote_sent" -> quote(eventId, type, data, id);
                            case "payments.refund_case_updated", "payments.refund_issued" ->
                                refund(eventId, type, data, id);
                            case OFFER -> offer(eventId, type, data);
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
            Map.entry("booking.confirmed", "{0} confirmed your booking for {1}"),
            Map.entry("booking.confirmed.title", "Booking confirmed"),
            Map.entry("booking.reminder", "Reminder: {0} is booked for {1}"),
            Map.entry("booking.reminder.title", "Your booking is tomorrow"),
            Map.entry("booking.en_route", "{0} is on the way. See the live arrival time in the app."),
            Map.entry("booking.en_route.title", "Your provider is on the way"),
            Map.entry("booking.completed", "{0} marked the job done. Check the photos and sign off in the app."),
            Map.entry("booking.completed.title", "Job done — please sign off"),
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
            Map.entry("run.unassigned.title", "Run changed"));

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
            Map.entry("booking.confirmed", "{0} a confirmé votre réservation du {1}"),
            Map.entry("booking.confirmed.title", "Réservation confirmée"),
            Map.entry("booking.reminder", "Rappel : {0} est réservé pour le {1}"),
            Map.entry("booking.reminder.title", "Votre réservation est demain"),
            Map.entry("booking.en_route", "{0} est en route. Voyez l’heure d’arrivée en direct dans l’appli."),
            Map.entry("booking.en_route.title", "Votre prestataire est en route"),
            Map.entry("booking.completed", "{0} a terminé les travaux. Vérifiez les photos et approuvez dans l’appli."),
            Map.entry("booking.completed.title", "Travaux terminés — à approuver"),
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
            Map.entry("run.unassigned.title", "Tournée modifiée"));
}
