package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every template and variant renders in English and French, HTML and text, with the CASL footer. */
class EmailTemplatesTest {

    static final EmailTemplates TEMPLATES = new EmailTemplates(
            "Northline Marketplace Inc. · 1200 – 8th Avenue SW, Calgary, Alberta T2P 1B5, Canada",
            "support@northline.ca");
    static final URI UNSUBSCRIBE = URI.create("http://localhost:8080/api/v1/email/unsubscribe?t=token");

    static Stream<Arguments> samples() {
        return EmailContent.samples().entrySet().stream()
                .flatMap(e -> Stream.of(EmailLocales.ENGLISH, EmailLocales.FRENCH)
                        .map(locale -> Arguments.of(e.getKey(), e.getValue(), locale)));
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("samples")
    void rendersEveryTemplate_inBothLanguages(String name, EmailContent content, Locale locale) {
        var email = TEMPLATES.render(content, locale, UNSUBSCRIBE);

        assertThat(email.subject())
                .isNotBlank()
                .doesNotContain("{", "}", "??", "\n")
                .contains(content.businessName());
        for (var body : new String[] {email.html(), email.text()}) {
            assertThat(body)
                    .contains(content.businessName())
                    .contains("1200 – 8th Avenue SW, Calgary") // CASL: mailing address
                    .contains("support@northline.ca") // CASL: contact
                    .doesNotContain("??", "${", "#{", "[(", "{0}", "{1}", "{2}", "null");
        }
        assertThat(email.html())
                .startsWith("<!DOCTYPE html>")
                .contains("lang=\"" + locale.toLanguageTag() + "\"")
                .contains("href=\"http://localhost:3100/")
                .doesNotContain("<style", "class=\"", "th:text", "th:style", "var(--"); // inline styles only
        assertThat(email.text()).doesNotContain("<", ">").contains("http://localhost:3100/");
        if (content.purpose().needsUnsubscribe()) {
            assertThat(email.html()).contains("href=\"" + UNSUBSCRIBE + "\"");
            assertThat(email.text()).contains(UNSUBSCRIBE.toString());
        } else {
            assertThat(email.html()).doesNotContain(UNSUBSCRIBE.toString());
            assertThat(email.text()).doesNotContain(UNSUBSCRIBE.toString());
        }
    }

    @Test
    void frenchReadsFrench_withCanadianFormats() {
        var payout = EmailContent.samples().get("payout-sent");
        var en = TEMPLATES.render(payout, Locale.CANADA, UNSUBSCRIBE);
        var fr = TEMPLATES.render(payout, Locale.forLanguageTag("fr-CA"), UNSUBSCRIBE);

        assertThat(en.subject()).isEqualTo("$814.37 is on its way to your bank — Prairie Wrench");
        assertThat(fr.subject()).isEqualTo("814,37 $ est en route vers votre banque — Prairie Wrench");
        assertThat(en.text()).contains("Instant payout fee: −$8.23", "Friday, October 2, 2026 at 9:30");
        assertThat(fr.text())
                .contains("Frais de versement instantané: −8,23\u00a0$", "vendredi 2 octobre 2026 à 09 h 30");
        assertThat(fr.html()).contains("Se désabonner", "Envoyé par Northline Marketplace Inc.");
    }

    @Test
    void invitation_isTransactional_namesTheRoleInTheReadersLanguage() {
        var invitation = EmailContent.samples().get("team-invitation");
        var fr = TEMPLATES.render(invitation, Locale.CANADA_FRENCH, null);

        assertThat(invitation.purpose()).isEqualTo(EmailContent.Purpose.TRANSACTIONAL);
        assertThat(fr.subject()).isEqualTo("Ravi Sandhu vous invite à rejoindre Prairie Wrench sur Northline");
        assertThat(fr.text())
                .contains(
                        "en tant que Technicien",
                        "http://localhost:3100/invite/sample-token",
                        "expire le vendredi 9 octobre 2026")
                .contains("Ceci est un message de service, pas de la publicité");
        assertThat(fr.html()).contains("Accepter l’invitation");
    }

    @Test
    void notificationsWithoutAnUnsubscribeLink_areRefused() {
        var payout = EmailContent.samples().get("payout-sent");

        assertThatThrownBy(() -> TEMPLATES.render(payout, Locale.CANADA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsubscribe");
    }

    @Test
    void userText_isEscapedInHtml() {
        var content = new EmailContent.TeamInvitation(
                "Bob's <b>Garage</b> & Co",
                "Eve <script>",
                "owner",
                URI.create("http://localhost:3100/invite/x"),
                java.time.Instant.parse("2026-10-09T15:00:00Z"));

        var html = TEMPLATES.render(content, Locale.CANADA, null).html();

        assertThat(html).doesNotContain("<b>Garage", "<script>").contains("&lt;b&gt;Garage&lt;/b&gt; &amp; Co");
    }

    @Test
    void everyEnglishMessage_hasAFrenchTranslation() throws Exception {
        var en = load("email/messages.properties");
        var fr = load("email/messages_fr.properties");

        assertThat(fr.stringPropertyNames()).isEqualTo(en.stringPropertyNames());
        assertThat(fr.stringPropertyNames())
                .filteredOn(k -> !k.equals("brand"))
                .allSatisfy(k -> assertThat(fr.getProperty(k)).isNotBlank());
        assertThat(en.values()).noneMatch(v -> v.toString().contains("'"));
        assertThat(fr.values()).noneMatch(v -> v.toString().contains("'"));
    }

    @Test
    void theUnsubscribePage_rendersEachState() {
        var confirm = TEMPLATES.page(
                "unsubscribe",
                Locale.CANADA_FRENCH,
                java.util.Map.of("state", "confirm", "eventLabel", "Versements", "token", "t0k", "action", "/x"));
        var invalid = TEMPLATES.page("unsubscribe", Locale.CANADA, java.util.Map.of("state", "invalid"));

        assertThat(confirm)
                .contains("Ne plus recevoir de courriels « Versements »?", "value=\"t0k\"", "method=\"post\"");
        assertThat(invalid).contains("This unsubscribe link isn’t valid");
    }

    private static Properties load(String resource) throws Exception {
        var props = new Properties();
        try (var in = EmailTemplatesTest.class.getClassLoader().getResourceAsStream(resource);
                var reader = new java.io.InputStreamReader(
                        java.util.Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
