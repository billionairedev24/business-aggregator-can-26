package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The email colours are copies of the design tokens: they must stay equal to tokens.json. */
class EmailBrandTest {

    @Test
    void baseColours_matchTheDesignTokens() throws Exception {
        var tokens = Files.readString(Path.of("../../web/packages/tokens/tokens.json"));

        assertThat(token(tokens, "accent")).isEqualToIgnoringCase(EmailBrand.ACCENT);
        assertThat(token(tokens, "accent-2")).isEqualToIgnoringCase(EmailBrand.ACCENT_2);
        assertThat(token(tokens, "highlight")).isEqualToIgnoringCase(EmailBrand.HIGHLIGHT);
        assertThat(token(tokens, "bg")).isEqualToIgnoringCase(EmailBrand.BG);
        assertThat(token(tokens, "text")).isEqualToIgnoringCase(EmailBrand.TEXT);
    }

    private static String token(String json, String name) {
        var m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"(#[0-9A-Fa-f]{6})\"")
                .matcher(json);
        assertThat(m.find()).as("token %s in tokens.json", name).isTrue();
        return m.group(1);
    }
}
