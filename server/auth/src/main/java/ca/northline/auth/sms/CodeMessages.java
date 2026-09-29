package ca.northline.auth.sms;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What the person receives, in their language: French for a {@code fr} locale, English otherwise. Each SMS fits one
 * GSM-7 segment (at most 160 characters, and {@code é} is in the GSM-7 alphabet — an accent outside it would switch the
 * message to UCS-2 and double the cost); the voice text reads the digits one by one, twice.
 */
enum CodeMessages {
    EN(
            "en-US",
            "Joanna",
            "Northline: your verification code is %s. It expires in 10 minutes. Never share it.",
            "Hello from Northline. Your verification code is: %1$s. Again, your code is: %1$s. Goodbye."),
    FR(
            "fr-CA",
            "Chantal",
            "Northline : votre code de vérification est %s. Il expire dans 10 minutes. Ne le partagez jamais.",
            "Bonjour, ici Northline. Votre code de vérification est : %1$s. Je répète, votre code est : %1$s. Au revoir.");

    private final String languageTag;
    private final String pollyVoice;
    private final String sms;
    private final String voice;

    CodeMessages(String languageTag, String pollyVoice, String sms, String voice) {
        this.languageTag = languageTag;
        this.pollyVoice = pollyVoice;
        this.sms = sms;
        this.voice = voice;
    }

    static CodeMessages of(Locale locale) {
        return "fr".equals(locale.getLanguage()) ? FR : EN;
    }

    /**
     * BCP 47 tag of the spoken language, matching {@link #pollyVoice()}: {@code fr-CA}, and {@code en-US} for English
     * (Amazon Polly has no Canadian English voice; the US one reads a code the same way).
     */
    String languageTag() {
        return languageTag;
    }

    /** Amazon Polly voice that reads the code (Twilio {@code Polly.<name>}, AWS {@code VoiceId}). */
    String pollyVoice() {
        return pollyVoice;
    }

    String sms(String code) {
        return sms.formatted(code);
    }

    /** Spoken text: the digits separated ("1, 2, 3, 4, 5, 6") so text-to-speech reads them one at a time. */
    String voice(String code) {
        return voice.formatted(code.chars().mapToObj(Character::toString).collect(Collectors.joining(", ")));
    }
}
