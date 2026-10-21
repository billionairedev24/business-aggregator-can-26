package ca.northline.sms;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * S-157: the one-word replies a person may text to Northline's number — opt out (CASL: an unsubscribe mechanism by the
 * same means; the carriers' and Twilio's STOP words, with the French ones), opt back in, or ask who is texting. Case,
 * accents and surrounding punctuation don't matter ({@code Arrêt!} is {@code ARRET}); anything longer is no keyword.
 */
public final class SmsKeywords {

    public enum Keyword {
        STOP,
        START,
        HELP
    }

    static final Set<String> STOP =
            Set.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT", "ARRET", "DESABONNER", "DESABONNEMENT");
    static final Set<String> START = Set.of("START", "UNSTOP", "YES", "OUI", "ABONNER");
    static final Set<String> HELP = Set.of("HELP", "INFO", "AIDE");

    private SmsKeywords() {}

    public static Optional<Keyword> of(String body) {
        var word = Normalizer.normalize(body.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
                .toUpperCase(Locale.ROOT);
        if (STOP.contains(word)) {
            return Optional.of(Keyword.STOP);
        }
        if (START.contains(word)) {
            return Optional.of(Keyword.START);
        }
        return HELP.contains(word) ? Optional.of(Keyword.HELP) : Optional.empty();
    }
}
