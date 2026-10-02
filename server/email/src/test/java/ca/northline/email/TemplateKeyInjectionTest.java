package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S-104: Thymeleaf pre-processes {@code __${…}__} into the expression that is then evaluated, so a value that becomes
 * part of a message key must be a code. A reviewer's free-text reason in {@code listing-rejected} (dishes accepted any
 * text) would otherwise have been evaluated as an expression in the api.
 */
class TemplateKeyInjectionTest {

    private static final URI LINK = URI.create("http://localhost:3100/b/01J9ZD3V00000000000000PDB1/kitchen/menu");

    @Test
    void aReasonThatIsNotACode_isRefusedBeforeRendering() {
        var hostile = new EmailContent.ListingRejected(
                "Pho Dau Bo", "dish", "Pho tai", List.of("other}+${7*7}+#{x"), null, LINK);

        assertThatThrownBy(() -> EmailTemplatesTest.TEMPLATES.render(hostile, EmailLocales.ENGLISH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasons");
    }

    @Test
    void aKindThatIsNotACode_isRefused_andCodesStillRender() {
        var hostile =
                new EmailContent.ListingRejected("Pho Dau Bo", "dish x", "Pho tai", List.of("pricing"), null, LINK);
        assertThatThrownBy(() -> EmailTemplatesTest.TEMPLATES.render(hostile, EmailLocales.FRENCH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");

        var fine = new EmailContent.ListingRejected(
                "Pho Dau Bo", "dish", "Pho tai", List.of("pricing", "other"), "Price is 3× the area's", LINK);
        assertThat(EmailTemplatesTest.TEMPLATES
                        .render(fine, EmailLocales.ENGLISH, null)
                        .text())
                .contains("Price is 3× the area's");
    }

    /** Every variable a template pre-processes into a key is one the guard checks (new templates included). */
    @Test
    void everyPreprocessedVariable_isGuarded() throws IOException, URISyntaxException {
        var dir = Path.of(
                Objects.requireNonNull(EmailTemplates.class.getClassLoader().getResource("email/templates"))
                        .toURI());
        var preprocessed = Pattern.compile("__\\$\\{([^}]*)}__");
        var identifier = Pattern.compile("(?<![.'\\w])([a-zA-Z_][\\w]*)(?:\\.(\\w+))?(?![\\w'(])");
        var used = new HashSet<String>();
        try (var files = Files.list(dir)) {
            for (var file : files.toList()) {
                var matcher = preprocessed.matcher(Files.readString(file));
                while (matcher.find()) {
                    var expression = matcher.group(1).replaceAll("'[^']*'", "''");
                    identifier.matcher(expression).results().forEach(m -> used.add(m.group(1)));
                }
            }
        }
        var loopItems = List.of("r", "c"); // th:each="r : ${reasons}", th:each="c : ${checks}"
        var derived = List.of("bodyKey"); // th:with from change and decision
        assertThat(used)
                .isNotEmpty()
                .allSatisfy(name -> assertThat(EmailTemplates.KEY_VARIABLES.contains(name)
                                || loopItems.contains(name)
                                || derived.contains(name))
                        .as(
                                "%s is pre-processed into a message key but not checked by EmailTemplates.requireCodes",
                                name)
                        .isTrue());
        assertThat(EmailTemplates.KEY_LISTS).containsExactlyInAnyOrder("reasons", "checks");
    }
}
