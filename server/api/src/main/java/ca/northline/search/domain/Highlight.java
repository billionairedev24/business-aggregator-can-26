package ca.northline.search.domain;

import java.text.Normalizer;
import java.util.List;

/**
 * The typed part of a suggestion, for bold rendering: {@code start} and {@code length} in UTF-16 units of the
 * suggestion's text. Matching ignores case and accents ("mec" marks "Méc" in "Mécanicien") and starts at a word.
 */
public record Highlight(int start, int length) {

    /** Where {@code typed} starts a word of {@code text}; empty when it doesn't (then nothing is bold). */
    public static List<Highlight> of(String text, String typed) {
        var folded = fold(text);
        var prefix = fold(typed.strip());
        if (prefix.isEmpty() || folded.length() != text.length()) {
            return List.of();
        }
        var from = 0;
        while (true) {
            var at = folded.indexOf(prefix, from);
            if (at < 0) {
                return List.of();
            }
            if (at == 0 || !Character.isLetterOrDigit(folded.charAt(at - 1))) {
                return List.of(new Highlight(at, prefix.length()));
            }
            from = at + 1;
        }
    }

    /** Lower case without diacritics, one char per char (é → e), so indexes line up with the original text. */
    static String fold(String text) {
        var out = new StringBuilder(text.length());
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            var base =
                    Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).charAt(0);
            out.append(Character.toLowerCase(base));
        }
        return out.toString();
    }
}
