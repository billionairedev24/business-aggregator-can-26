package ca.northline.sms;

import java.util.Locale;

/**
 * The language a call is read in, with the Amazon Polly voice both providers use (Twilio {@code Polly.<name>}, AWS
 * {@code VoiceId}): French for any {@code fr} locale, English otherwise. {@code en-US}: Polly has no Canadian English
 * voice, and the US one reads digits and short sentences the same way.
 */
public enum SpokenLanguage {
    EN("en-US", "Joanna"),
    FR("fr-CA", "Chantal");

    private final String languageTag;
    private final String pollyVoice;

    SpokenLanguage(String languageTag, String pollyVoice) {
        this.languageTag = languageTag;
        this.pollyVoice = pollyVoice;
    }

    public static SpokenLanguage of(Locale locale) {
        return "fr".equals(locale.getLanguage()) ? FR : EN;
    }

    public String languageTag() {
        return languageTag;
    }

    public String pollyVoice() {
        return pollyVoice;
    }
}
