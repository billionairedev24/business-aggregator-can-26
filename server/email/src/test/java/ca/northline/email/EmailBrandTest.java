package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The email colours are copies of the palette brand.json selects: they must stay equal to it. */
class EmailBrandTest {

    private static final Path TOKENS = Path.of("../../web/packages/tokens");

    @Test
    void colours_matchTheSelectedPalette() throws Exception {
        var brand = Files.readString(TOKENS.resolve("brand.json"));
        var name = value(brand, "palette", "[a-z0-9-]+");
        var palette = Files.readString(TOKENS.resolve("palettes/" + name + ".json"));

        assertThat(colour(palette, "primary")).isEqualToIgnoringCase(EmailBrand.ACCENT);
        assertThat(colour(palette, "accentInk")).isEqualToIgnoringCase(EmailBrand.ACCENT_2);
        assertThat(colour(palette, "accent")).isEqualToIgnoringCase(EmailBrand.HIGHLIGHT);
        assertThat(colour(palette, "ground")).isEqualToIgnoringCase(EmailBrand.BG);
        assertThat(colour(palette, "ink")).isEqualToIgnoringCase(EmailBrand.TEXT);
        assertThat(colour(palette, "surface")).isEqualToIgnoringCase(EmailBrand.SURFACE);
        assertThat(colour(palette, "border")).isEqualToIgnoringCase(EmailBrand.DIVIDER);
        assertThat(colour(palette, "textMuted")).isEqualToIgnoringCase(EmailBrand.MUTED);
        assertThat(colour(palette, "accentTint")).isEqualToIgnoringCase(EmailBrand.HIGHLIGHT_100);
        assertThat(colour(palette, "primaryText")).isEqualToIgnoringCase(EmailBrand.ON_ACCENT);
        assertThat(EmailBrand.FONT_BODY).startsWith(value(palette, "family", "[^\"]+"));
    }

    private static String colour(String json, String name) {
        return value(json, name, "#[0-9A-Fa-f]{6}");
    }

    private static String value(String json, String name, String valuePattern) {
        var m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"(" + valuePattern + ")\"")
                .matcher(json);
        assertThat(m.find()).as("%s in the tokens", name).isTrue();
        return m.group(1);
    }
}
