package ca.northline.messaging.application;

import ca.northline.identity.api.NotificationContacts;
import ca.northline.messaging.application.Consents.ConsentStore;
import ca.northline.messaging.application.Consents.ManageConsents;
import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentSource;
import ca.northline.messaging.domain.ConsentWordings;
import ca.northline.platform.EmailProperties;
import ca.northline.sms.PhoneNumbers;
import ca.northline.sms.SmsKeywords;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-157, inbound texts (CASL s. 11: an unsubscribe mechanism by the same electronic means, honoured within 10 business
 * days — here at once, in the webhook's own transaction):
 *
 * <ul>
 *   <li><b>STOP</b> (and ARRET, UNSUBSCRIBE, DÉSABONNER…) withdraws {@code marketing_sms} of every active account with
 *       that number (source {@code sms_keyword}) and confirms by reply. The provider (Twilio's opt-out handling) then
 *       stops every text from that number until START — the reply says so.
 *   <li><b>START</b> turns texts back on. It gives the offers consent back <i>only</i> when that consent was withdrawn by a
 *       STOP (the {@code keyword.sms} wording, quoted in the reply); otherwise offers stay off — a START is not
 *       express consent to something the person never asked for.
 *   <li><b>HELP</b> answers who is texting and how to reach them (sender identification).
 * </ul>
 *
 * A retried webhook (same message id) changes nothing and is not answered twice. Anything else is not answered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class SmsKeywordService implements InboundSms {

    static final Duration KEEP = Duration.ofDays(90);

    private final SmsWebhookLog webhookLog;
    private final NotificationContacts contacts;
    private final ManageConsents consents;
    private final ConsentStore store;
    private final EmailProperties email;
    private final MeterRegistry meters;
    private final Clock clock;

    @Override
    public Optional<String> received(Received text) {
        var keyword = SmsKeywords.of(text.body());
        var code = keyword.map(k -> k.name().toLowerCase(Locale.ROOT)).orElse("none");
        var from = text.from().strip();
        if (!webhookLog.first(
                text.provider(), text.messageId(), ConsentEvidence.addressHash(from), code, clock.instant())) {
            log.info("Inbound text {} from {} already handled", text.messageId(), PhoneNumbers.masked(from));
            return Optional.empty();
        }
        meters.counter("northline.sms.inbound", "keyword", code).increment();
        if (keyword.isEmpty()) {
            return Optional.empty();
        }
        var users = PhoneNumbers.isE164(from) ? contacts.usersWithPhone(from) : List.<String>of();
        var language = language(users);
        var reply = switch (keyword.get()) {
            case STOP -> {
                users.forEach(u -> consents.change(u, change(false, language)));
                yield Replies.stop(legalName(), language);
            }
            case START -> {
                var restored = 0;
                for (var u : users) {
                    var latest = store.latest(u, ConsentCategory.MARKETING_SMS);
                    if (latest.filter(r -> !r.granted() && r.source() == ConsentSource.SMS_KEYWORD)
                            .isPresent()) {
                        consents.change(u, change(true, language));
                        restored++;
                    }
                }
                yield restored > 0
                        ? Replies.startWithOffers(legalName(), language)
                        : Replies.start(legalName(), language);
            }
            case HELP -> Replies.help(legalName(), email.contact(), language);
        };
        webhookLog.applied(text.provider(), text.messageId(), users.size());
        log.info(
                "Inbound {} from {} applied to {} account(s)",
                code.toUpperCase(Locale.ROOT),
                PhoneNumbers.masked(from),
                users.size());
        return Optional.of(reply);
    }

    @Override
    public void status(Status status) {
        webhookLog.status(status, clock.instant());
        meters.counter("northline.sms.delivery", "status", status.status()).increment();
        if ("failed".equals(status.status()) || "undelivered".equals(status.status())) {
            log.warn("Text {} {}: error {}", status.messageId(), status.status(), status.errorCode());
        }
    }

    /** Nightly: the webhook log is kept 90 days (no number or text in it; the consent records keep the proof). */
    @Scheduled(
            cron = "${northline.messaging.sms-webhook-purge-cron:0 33 4 * * *}",
            zone = "${northline.region.platform-zone}")
    void purge() {
        var purged = webhookLog.purgeBefore(clock.instant().minus(KEEP));
        if (purged > 0) {
            log.info("Purged {} SMS webhook rows older than {}", purged, KEEP);
        }
    }

    private static Consents.Change change(boolean granted, String language) {
        return new Consents.Change(
                ConsentCategory.MARKETING_SMS,
                granted,
                ConsentSource.SMS_KEYWORD,
                granted ? keywordWording() : null,
                "fr".equals(language) ? "fr" : "en",
                ConsentEvidence.NONE);
    }

    private static String keywordWording() {
        return ConsentWordings.current(ConsentCategory.MARKETING_SMS, ConsentWordings.Surface.SMS_KEYWORD)
                .map(ConsentWordings.Wording::version)
                .orElseThrow();
    }

    /** The account's language when the number is someone's (the first, verified one first); both when unknown. */
    private String language(List<String> users) {
        return users.isEmpty()
                ? "both"
                : contacts.contact(users.getFirst())
                        .map(c -> "fr".equals(c.locale().getLanguage()) ? "fr" : "en")
                        .orElse("both");
    }

    private String legalName() {
        var name = email.legalName();
        return name == null || name.isBlank() ? "Northline" : name.strip();
    }

    /** The replies (one segment or two; the sender named first, CASL sender identification). */
    static final class Replies {
        private Replies() {}

        static String stop(String legal, String language) {
            return pick(
                    language,
                    legal + ": you’re unsubscribed — no more offers by text. Texts from this number stop until you"
                            + " reply START. HELP for help.",
                    legal + " : vous êtes désabonné — plus d’offres par texto. Les textos de ce numéro s’arrêtent"
                            + " jusqu’à ce que vous répondiez START. AIDE pour de l’aide.");
        }

        static String start(String legal, String language) {
            return pick(
                    language,
                    legal + ": texts are back on (orders, bookings, codes). Offers by text stay off — turn them on in"
                            + " Account › Notifications. Reply STOP to opt out.",
                    legal + " : les textos sont rétablis (commandes, réservations, codes). Les offres par texto"
                            + " restent désactivées — activez-les dans Compte › Notifications. Répondez ARRET pour"
                            + " vous désabonner.");
        }

        static String startWithOffers(String legal, String language) {
            var wording = ConsentWordings.current(ConsentCategory.MARKETING_SMS, ConsentWordings.Surface.SMS_KEYWORD)
                    .orElseThrow();
            return pick(
                    language,
                    legal + ": texts and offers are back on. " + wording.text("en", legal),
                    legal + " : les textos et les offres sont rétablis. " + wording.text("fr", legal));
        }

        static String help(String legal, String contact, String language) {
            return pick(
                    language,
                    legal + ": marketplace updates and offers. Help: " + contact
                            + ". Reply STOP to unsubscribe, START to resubscribe.",
                    legal + " : avis et offres du marché. Aide : " + contact
                            + ". Répondez ARRET pour vous désabonner, START pour vous réabonner.");
        }

        private static String pick(String language, String en, String fr) {
            return switch (language) {
                case "fr" -> fr;
                case "en" -> en;
                default -> en + " / " + fr;
            };
        }
    }
}
