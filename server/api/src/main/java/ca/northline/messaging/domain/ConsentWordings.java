package ca.northline.messaging.domain;

import java.util.List;
import java.util.Optional;

/**
 * The consent wordings Northline shows (S-108), en and fr-CA, one per category and place. A consent record stores the
 * version that was shown, so the exact words can be produced for any record: <b>a published wording is never
 * edited</b> — changed words get a new version, and {@code ConsentWordingsTest} pins every published one. The sender's
 * legal name is configuration ({@code EMAIL_LEGAL_NAME}) and is put into {@code {legalName}}; the requester's mailing
 * address and contact (CASL regulations s. 4) are shown next to the wording from configuration too.
 *
 * <p>Each wording says what is sent, by whom, on which channel, and that consent can be withdrawn at any time — CASL
 * s. 10(1) and Québec's Law 25 (a separate request per purpose, in clear and simple terms). Nothing is pre-ticked: the
 * person's own act (a switch, a choice) records the grant.
 */
public final class ConsentWordings {

    /** Where the wording is shown. */
    public enum Surface {
        /** Customers: Account › Notifications on the web and in the app, sign-up, checkout. */
        ACCOUNT,
        /** Team members: Studio › Settings › Notifications. */
        STUDIO
    }

    /**
     * @param version stored in {@code consent_records.wording_version}
     */
    public record Wording(String version, ConsentCategory category, Surface surface, String en, String fr) {

        /** The words in {@code language} ({@code fr} or anything else = English) with the legal name filled in. */
        public String text(String language, String legalName) {
            return ("fr".equals(language) ? fr : en).replace("{legalName}", legalName);
        }
    }

    private static final List<Wording> ALL = List.of(
            new Wording(
                    "account.email.2026-10",
                    ConsentCategory.MARKETING_EMAIL,
                    Surface.ACCOUNT,
                    "Yes, {legalName} may email me Northline’s offers, rewards and news. I can withdraw my consent at any"
                            + " time with the unsubscribe link in every email or in Account › Notifications.",
                    "Oui, {legalName} peut m’envoyer par courriel les offres, récompenses et nouvelles de Northline. Je"
                            + " peux retirer mon consentement en tout temps avec le lien de désabonnement de chaque"
                            + " courriel ou dans Compte › Notifications."),
            new Wording(
                    "account.sms.2026-10",
                    ConsentCategory.MARKETING_SMS,
                    Surface.ACCOUNT,
                    "Yes, {legalName} may text me Northline’s offers and rewards. I can withdraw my consent at any time"
                            + " with the opt-out link in every text or in Account › Notifications.",
                    "Oui, {legalName} peut m’envoyer par texto les offres et récompenses de Northline. Je peux retirer"
                            + " mon consentement en tout temps avec le lien de désabonnement de chaque texto ou dans"
                            + " Compte › Notifications."),
            new Wording(
                    "account.push.2026-10",
                    ConsentCategory.MARKETING_PUSH,
                    Surface.ACCOUNT,
                    "Yes, {legalName} may send me Northline’s offers and rewards as app notifications. I can turn them"
                            + " off at any time in Account › Notifications.",
                    "Oui, {legalName} peut m’envoyer les offres et récompenses de Northline en notifications de"
                            + " l’application. Je peux les désactiver en tout temps dans Compte › Notifications."),
            new Wording(
                    "studio.email.2026-10",
                    ConsentCategory.MARKETING_EMAIL,
                    Surface.STUDIO,
                    "Yes, {legalName} may email me news, offers and tips for businesses selling on Northline. I can"
                            + " withdraw my consent at any time with the unsubscribe link in every email or here.",
                    "Oui, {legalName} peut m’envoyer par courriel des nouvelles, des offres et des conseils pour les"
                            + " entreprises qui vendent sur Northline. Je peux retirer mon consentement en tout temps avec"
                            + " le lien de désabonnement de chaque courriel ou ici."));

    private ConsentWordings() {}

    public static List<Wording> all() {
        return ALL;
    }

    /** The wording shown now for a category in a place (the last published one). */
    public static Optional<Wording> current(ConsentCategory category, Surface surface) {
        return ALL.reversed().stream()
                .filter(w -> w.category() == category && w.surface() == surface)
                .findFirst();
    }

    public static Optional<Wording> find(String version) {
        return ALL.stream().filter(w -> w.version().equals(version)).findFirst();
    }
}
