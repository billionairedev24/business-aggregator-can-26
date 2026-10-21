package ca.northline.messaging.domain;

import ca.northline.email.MessageClasses;
import java.util.List;
import java.util.Optional;

/**
 * The consent wordings (S-108) in the messaging module's types. Since S-157 the catalogue itself is the shared email
 * library's {@link ca.northline.email.ConsentWordings} (northline-auth shows the sign-up ones); a published wording is
 * never edited — {@code ConsentWordingsTest} pins every one.
 */
public final class ConsentWordings {

    /** Where the wording is shown. */
    public enum Surface {
        /** Customers: Account › Notifications on the web and in the app, sign-up, checkout. */
        ACCOUNT,
        /** Team members: Studio › Settings › Notifications. */
        STUDIO,
        /** S-157: the reply to a START text. */
        SMS_KEYWORD
    }

    /** @param version stored in {@code consent_records.wording_version} */
    public record Wording(String version, ConsentCategory category, Surface surface, String en, String fr) {

        /** The words in {@code language} ({@code fr} or anything else = English) with the legal name filled in. */
        public String text(String language, String legalName) {
            return ("fr".equals(language) ? fr : en).replace("{legalName}", legalName);
        }
    }

    private static final List<Wording> ALL = ca.northline.email.ConsentWordings.all().stream()
            .map(ConsentWordings::of)
            .toList();

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

    private static Wording of(ca.northline.email.ConsentWordings.Wording w) {
        return new Wording(
                w.version(),
                ConsentCategory.ofChannel(w.category().channel()),
                Surface.valueOf(w.surface().name()),
                w.en(),
                w.fr());
    }

    /** The library's category for this module's (the same codes). */
    public static MessageClasses.ConsentCategory library(ConsentCategory category) {
        return MessageClasses.ConsentCategory.ofChannel(category.channel());
    }
}
