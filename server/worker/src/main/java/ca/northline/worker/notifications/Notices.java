package ca.northline.worker.notifications;

import ca.northline.email.EmailContent;
import ca.northline.email.EmailFormat;
import java.net.URI;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The money events the notifications consumer handles, and who sends what (docs/runbooks/notifications.md):
 *
 * <table>
 *   <tr><th>event</th><th>row</th><th>to</th><th>worker sends</th><th>api sends (S-13)</th></tr>
 *   <tr><td>payout_account.changed</td><td>— (security)</td><td>owners</td><td>SMS, always, even in quiet hours</td>
 *       <td>email, always</td></tr>
 *   <tr><td>payout.sent</td><td>payout</td><td>owners, bookkeepers</td><td>SMS, push</td><td>email</td></tr>
 *   <tr><td>payout.failed</td><td>payout</td><td>owners, bookkeepers</td><td>email, SMS, push</td><td>—</td></tr>
 *   <tr><td>dispute.updated / decided, refund.case_updated / issued</td><td>dispute</td><td>owners</td>
 *       <td>SMS, push</td><td>email</td></tr>
 * </table>
 *
 * Texts are one short line in the member's language (French for {@code fr}), amounts and dates formatted like the
 * emails ({@link EmailFormat}: CAD, the email time zone).
 */
public final class Notices {

    static final Set<String> OWNERS = Set.of("owner");
    static final Set<String> FINANCE = Set.of("owner", "bookkeeper");
    private static final Set<Channel> SMS_PUSH = EnumSet.of(Channel.SMS, Channel.PUSH);

    private Notices() {}

    /** The notice for an event, or empty when the event isn't one the team is told about. */
    public static Optional<Notice> of(String eventId, String type, int version, JsonNode data) {
        if (version != 1) {
            return Optional.empty(); // a new version needs its own wording here first (the schema changed)
        }
        var merchant = data.path("merchantId").asString("");
        return Optional.ofNullable(
                switch (type) {
                    case "payments.payout_account_changed" ->
                        new Notice(
                                eventId,
                                type,
                                version,
                                merchant,
                                null,
                                OWNERS,
                                EnumSet.of(Channel.SMS),
                                data,
                                bankAccount(data));
                    case "payments.payout_sent" ->
                        new Notice(
                                eventId, type, version, merchant, "payout", FINANCE, SMS_PUSH, data, payoutSent(data));
                    case "payments.payout_failed" ->
                        new Notice(
                                eventId,
                                type,
                                version,
                                merchant,
                                "payout",
                                FINANCE,
                                EnumSet.of(Channel.EMAIL, Channel.SMS, Channel.PUSH),
                                data,
                                payoutFailed(data));
                    case "payments.dispute_updated",
                            "payments.dispute_decided",
                            "payments.refund_case_updated",
                            "payments.refund_issued" ->
                        new Notice(
                                eventId, type, version, merchant, "dispute", OWNERS, SMS_PUSH, data, cases(type, data));
                    default -> null;
                });
    }

    private static Notice.Texts bankAccount(JsonNode data) {
        var effective = "effective".equals(data.path("phase").asString(""));
        var at = OffsetDateTime.parse(data.path("effectiveAt").asString()).toInstant();
        return texts(effective ? "bank.effective" : "bank.requested", "bank.title", (f, business) ->
                new Object[] {business, f.dateTime(at)});
    }

    private static Notice.Texts payoutSent(JsonNode data) {
        var net = data.path("amountCents").asLong() - data.path("feeCents").asLong();
        return texts("payout.sent", "payout.sent.title", (f, business) -> new Object[] {f.money(net), business});
    }

    private static Notice.Texts payoutFailed(JsonNode data) {
        var outcome = data.path("outcome").asString("failed");
        var amount = data.path("amountCents").asLong();
        var code =
                data.path("failureCode").isString() ? data.path("failureCode").asString() : null;
        var words = texts(
                "payout." + outcome, "payout.failed.title", (f, business) -> new Object[] {f.money(amount), business});
        return new Notice.Texts() {
            @Override
            public String text(String business, Locale locale, ZoneId zone) {
                return words.text(business, locale, zone);
            }

            @Override
            public String title(String business, Locale locale, ZoneId zone) {
                return words.title(business, locale, zone);
            }

            @Override
            public EmailContent email(String business, URI payoutsLink) {
                return new EmailContent.PayoutFailed(business, amount, outcome, code, payoutsLink);
            }
        };
    }

    private static Notice.Texts cases(String type, JsonNode data) {
        var caseNumber = data.path("caseNumber").asString("");
        var amount = data.path("amountCents").asLong();
        var respondBy = data.path("respondBy").isString()
                ? OffsetDateTime.parse(data.path("respondBy").asString()).toInstant()
                : null;
        var key = switch (type) {
            case "payments.dispute_updated" -> "dispute." + data.path("change").asString();
            case "payments.dispute_decided" -> "dispute.decided";
            case "payments.refund_case_updated" ->
                "refund." + data.path("change").asString();
            default -> "refund.issued";
        };
        var refund = type.startsWith("payments.refund");
        var refundCents = data.path("refundCents").asLong(amount);
        return texts(
                key, refund ? "refund.title" : "dispute.title", (f, business) -> new Object[] {
                    caseNumber, business, respondBy == null ? "" : f.dateTime(respondBy), f.money(refundCents)
                });
    }

    @FunctionalInterface
    private interface Arguments {
        Object[] of(EmailFormat format, String business);
    }

    private static Notice.Texts texts(String key, String titleKey, Arguments arguments) {
        return new Notice.Texts() {
            @Override
            public String text(String business, Locale locale, ZoneId zone) {
                return format(key, locale, arguments.of(EmailFormat.of(locale, zone), business));
            }

            @Override
            public String title(String business, Locale locale, ZoneId zone) {
                return format(titleKey, locale, arguments.of(EmailFormat.of(locale, zone), business));
            }
        };
    }

    static String format(String key, Locale locale, Object... args) {
        var french = "fr".equals(locale.getLanguage());
        var pattern = (french ? FR : EN).get(key);
        if (pattern == null) {
            throw new IllegalArgumentException("No notification text " + key);
        }
        return new MessageFormat(pattern, locale).format(args);
    }

    // One GSM-friendly line each; {1} is the business. MessageFormat: '' is an apostrophe (’ is used instead).
    static final Map<String, String> EN = Map.ofEntries(
            Map.entry(
                    "bank.requested",
                    "Northline: the payout bank account of {0} is changing. Payouts are paused"
                            + " until {1}. Not you? Contact Northline support now."),
            Map.entry(
                    "bank.effective",
                    "Northline: payouts of {0} now go to the new bank account. Not you? Contact"
                            + " Northline support now."),
            Map.entry("bank.title", "Payout bank account — {0}"),
            Map.entry("payout.sent", "Northline: a payout of {0} for {1} is on its way to your bank."),
            Map.entry("payout.sent.title", "Payout on its way — {1}"),
            Map.entry(
                    "payout.failed",
                    "Northline: your bank returned the payout of {0} for {1}. The money is back in"
                            + " your balance. Check your bank details in Payouts."),
            Map.entry(
                    "payout.canceled",
                    "Northline: the payout of {0} for {1} was canceled. The money is back in" + " your balance."),
            Map.entry("payout.failed.title", "Payout returned — {1}"),
            Map.entry("dispute.opened", "Northline: new dispute {0} for {1}. Respond by {2} in Refunds & disputes."),
            Map.entry(
                    "dispute.offer_declined",
                    "Northline: dispute {0} for {1}: your offer was declined. An agent" + " reviews the case."),
            Map.entry(
                    "dispute.offer_expired",
                    "Northline: dispute {0} for {1}: your offer expired. An agent reviews" + " the case."),
            Map.entry("dispute.decided", "Northline: dispute {0} for {1} was decided. Details in Refunds & disputes."),
            Map.entry("dispute.title", "Dispute {0} — {1}"),
            Map.entry(
                    "refund.requested",
                    "Northline: refund request {0} for {1}. Respond by {2} in Refunds &" + " disputes."),
            Map.entry("refund.approved", "Northline: refund {0} for {1} was approved."),
            Map.entry("refund.agent_review", "Northline: refund request {0} for {1} went to a Northline agent."),
            Map.entry("refund.denied", "Northline: refund request {0} for {1} was denied."),
            Map.entry("refund.issued", "Northline: refund {0} of {3} for {1} was paid to the customer."),
            Map.entry("refund.title", "Refund {0} — {1}"));

    static final Map<String, String> FR = Map.ofEntries(
            Map.entry(
                    "bank.requested",
                    "Northline : le compte bancaire de versement de {0} change. Les versements"
                            + " sont suspendus jusqu’au {1}. Pas vous? Écrivez au soutien Northline sans attendre."),
            Map.entry(
                    "bank.effective",
                    "Northline : les versements de {0} vont maintenant au nouveau compte"
                            + " bancaire. Pas vous? Écrivez au soutien Northline sans attendre."),
            Map.entry("bank.title", "Compte de versement — {0}"),
            Map.entry("payout.sent", "Northline : un versement de {0} pour {1} est en route vers votre banque."),
            Map.entry("payout.sent.title", "Versement en route — {1}"),
            Map.entry(
                    "payout.failed",
                    "Northline : votre banque a retourné le versement de {0} pour {1}. L’argent"
                            + " est de retour dans votre solde. Vérifiez vos coordonnées bancaires dans Versements."),
            Map.entry(
                    "payout.canceled",
                    "Northline : le versement de {0} pour {1} a été annulé. L’argent est de"
                            + " retour dans votre solde."),
            Map.entry("payout.failed.title", "Versement retourné — {1}"),
            Map.entry(
                    "dispute.opened",
                    "Northline : nouveau litige {0} pour {1}. Répondez d’ici le {2} dans"
                            + " Remboursements et litiges."),
            Map.entry(
                    "dispute.offer_declined",
                    "Northline : litige {0} pour {1} : votre offre a été refusée. Un" + " agent examine le dossier."),
            Map.entry(
                    "dispute.offer_expired",
                    "Northline : litige {0} pour {1} : votre offre a expiré. Un agent" + " examine le dossier."),
            Map.entry(
                    "dispute.decided",
                    "Northline : le litige {0} pour {1} a été tranché. Détails dans" + " Remboursements et litiges."),
            Map.entry("dispute.title", "Litige {0} — {1}"),
            Map.entry(
                    "refund.requested",
                    "Northline : demande de remboursement {0} pour {1}. Répondez d’ici le {2}"
                            + " dans Remboursements et litiges."),
            Map.entry("refund.approved", "Northline : le remboursement {0} pour {1} a été approuvé."),
            Map.entry(
                    "refund.agent_review",
                    "Northline : la demande de remboursement {0} pour {1} a été confiée à" + " un agent Northline."),
            Map.entry("refund.denied", "Northline : la demande de remboursement {0} pour {1} a été refusée."),
            Map.entry("refund.issued", "Northline : le remboursement {0} de {3} pour {1} a été versé au client."),
            Map.entry("refund.title", "Remboursement {0} — {1}"));
}
