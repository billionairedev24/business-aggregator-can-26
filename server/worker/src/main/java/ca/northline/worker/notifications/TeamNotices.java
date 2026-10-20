package ca.northline.worker.notifications;

import ca.northline.email.EmailContent;
import ca.northline.email.EmailFormat;
import java.net.URI;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * S-153: the operational alerts a business's team gets, by its Settings › Notifications rows (docs/runbooks/notifications.md):
 *
 * <table>
 *   <tr><th>event</th><th>row</th><th>to</th><th>Studio page</th></tr>
 *   <tr><td>quote.requested</td><td>quote_request</td><td>owners, technicians of every provider asked</td>
 *       <td>appointments</td></tr>
 *   <tr><td>message.sent by a customer</td><td>customer_message</td><td>owners</td><td>messages?thread=…</td></tr>
 *   <tr><td>booking.cancelled</td><td>new_booking</td><td>owners, technicians</td><td>appointments</td></tr>
 *   <tr><td>booking.balance_failed</td><td>new_booking</td><td>owners</td><td>appointments</td></tr>
 * </table>
 *
 * Consumed by the {@code personal-notifications} group (no retry topics), so an outage is deferred like a customer's
 * ({@link Notifier}). One line in the member's language with references, amounts and times only.
 */
public final class TeamNotices {

    /** The types whose team notices are deferred on an outage (their consumer group has no retry topics). */
    static final Set<String> DEFERRED = Set.of(
            "booking.quote_requested",
            "messaging.message_sent",
            "booking.booking_cancelled",
            "booking.booking_balance_failed");

    static final Set<String> OWNERS = Set.of("owner");
    static final Set<String> OPERATORS = Set.of("owner", "technician");
    private static final Set<Channel> ALL = EnumSet.allOf(Channel.class);

    private TeamNotices() {}

    public static List<Notice> of(String eventId, String type, int version, JsonNode data) {
        if (version != 1) {
            return List.of();
        }
        var merchant = data.path("merchantId").asString("");
        return switch (type) {
            case "booking.quote_requested" -> {
                var out = new ArrayList<Notice>();
                var respondBy = instant(data, "respondBy");
                var ref = data.path("quoteRef").asString("");
                for (var m : data.path("merchantIds")) {
                    out.add(new Notice(
                            eventId,
                            type,
                            version,
                            m.asString(),
                            "quote_request",
                            OPERATORS,
                            ALL,
                            data,
                            texts("team.quote_requested", "appointments", (f, _) ->
                                    new Object[] {ref, respondBy == null ? "" : f.dateTime(respondBy)})));
                }
                yield out;
            }
            case "messaging.message_sent" ->
                "customer".equals(data.path("threadKind").asString(""))
                                && "customer".equals(data.path("senderRole").asString(""))
                        ? List.of(new Notice(
                                eventId,
                                type,
                                version,
                                merchant,
                                "customer_message",
                                OWNERS,
                                ALL,
                                data,
                                texts(
                                        "team.customer_message",
                                        "messages?thread="
                                                + data.path("aggregateId").asString(""),
                                        (_, business) -> new Object[] {business})))
                        : List.of();
            case "booking.booking_cancelled" -> {
                var startsAt = instant(data, "startsAt");
                var fee = data.path("feeCents").asLong()
                        + data.path("feeTaxCents").asLong();
                yield List.of(new Notice(
                        eventId,
                        type,
                        version,
                        merchant,
                        "new_booking",
                        OPERATORS,
                        ALL,
                        data,
                        texts(
                                data.path("late").asBoolean(false) && fee > 0
                                        ? "team.booking_cancelled_fee"
                                        : "team.booking_cancelled",
                                "appointments",
                                (f, _) -> new Object[] {startsAt == null ? "" : f.dateTime(startsAt), f.money(fee)})));
            }
            case "booking.booking_balance_failed" -> {
                var startsAt = instant(data, "startsAt");
                var amount = data.path("amountCents").asLong()
                        + data.path("taxCents").asLong();
                var retry = data.path("retryAt").isString();
                yield List.of(new Notice(
                        eventId,
                        type,
                        version,
                        merchant,
                        "new_booking",
                        OWNERS,
                        ALL,
                        data,
                        texts(retry ? "team.balance_failed" : "team.balance_failed_final", "appointments", (f, _) ->
                                new Object[] {f.money(amount), startsAt == null ? "" : f.dateTime(startsAt)})));
            }
            default -> List.of();
        };
    }

    private static @Nullable Instant instant(JsonNode data, String field) {
        return data.path(field).isString()
                ? OffsetDateTime.parse(data.path(field).asString()).toInstant()
                : null;
    }

    @FunctionalInterface
    private interface Arguments {
        Object[] of(EmailFormat format, String business);
    }

    private static Notice.Texts texts(String key, String studioPage, Arguments arguments) {
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
                return ("fr".equals(locale.getLanguage()) ? "Northline : " : "Northline: ")
                        + text(business, locale, zone);
            }

            @Override
            public EmailContent email(String business, URI link, Locale locale, ZoneId zone) {
                return new EmailContent.TeamUpdate(
                        business, title(business, locale, zone), text(business, locale, zone), link);
            }

            @Override
            public String studioPage() {
                return studioPage;
            }
        };
    }

    static String format(String key, Locale locale, Object... args) {
        var pattern = ("fr".equals(locale.getLanguage()) ? FR : EN).get(key);
        if (pattern == null) {
            throw new IllegalArgumentException("No notification text " + key);
        }
        return new MessageFormat(pattern, locale).format(args);
    }

    // {0}/{1} per key as the arguments above. MessageFormat: ’ instead of an apostrophe.
    static final Map<String, String> EN = Map.ofEntries(
            Map.entry("team.quote_requested", "New quote request {0}. Answer by {1} in the Studio."),
            Map.entry("team.quote_requested.title", "New quote request {0}"),
            Map.entry("team.customer_message", "A customer wrote to {0}. Reply in Messages."),
            Map.entry("team.customer_message.title", "New message from a customer"),
            Map.entry("team.booking_cancelled", "A customer cancelled the booking of {0} — the time is free again."),
            Map.entry("team.booking_cancelled.title", "Booking cancelled"),
            Map.entry(
                    "team.booking_cancelled_fee",
                    "A customer cancelled the booking of {0} late. The {1} cancellation fee is held for you."),
            Map.entry("team.booking_cancelled_fee.title", "Late cancellation"),
            Map.entry(
                    "team.balance_failed",
                    "The balance of {0} for the job of {1} couldn’t be held yet. We’ll try again and asked the"
                            + " customer to pay it."),
            Map.entry("team.balance_failed.title", "Balance not held yet"),
            Map.entry(
                    "team.balance_failed_final",
                    "The balance of {0} for the job of {1} couldn’t be held. Talk to the customer before you go."),
            Map.entry("team.balance_failed_final.title", "Balance not held"));

    static final Map<String, String> FR = Map.ofEntries(
            Map.entry("team.quote_requested", "Nouvelle demande de devis {0}. Répondez d’ici le {1} dans le Studio."),
            Map.entry("team.quote_requested.title", "Nouvelle demande de devis {0}"),
            Map.entry("team.customer_message", "Un client a écrit à {0}. Répondez dans Messages."),
            Map.entry("team.customer_message.title", "Nouveau message d’un client"),
            Map.entry(
                    "team.booking_cancelled",
                    "Un client a annulé la réservation du {0}. La plage horaire est de nouveau libre."),
            Map.entry("team.booking_cancelled.title", "Réservation annulée"),
            Map.entry(
                    "team.booking_cancelled_fee",
                    "Un client a annulé tardivement la réservation du {0}. Les frais d’annulation de {1} sont"
                            + " retenus pour vous."),
            Map.entry("team.booking_cancelled_fee.title", "Annulation tardive"),
            Map.entry(
                    "team.balance_failed",
                    "Le solde de {0} pour les travaux du {1} n’a pas encore pu être retenu. Nous réessaierons et"
                            + " avons demandé au client de le payer."),
            Map.entry("team.balance_failed.title", "Solde pas encore retenu"),
            Map.entry(
                    "team.balance_failed_final",
                    "Le solde de {0} pour les travaux du {1} n’a pas pu être retenu. Parlez au client avant de"
                            + " vous déplacer."),
            Map.entry("team.balance_failed_final.title", "Solde non retenu"));
}
