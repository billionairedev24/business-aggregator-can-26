package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.email.MessageClasses.ConsentCategory;
import ca.northline.email.MessageClasses.MessageClass;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * S-108: every email template is classified (commercial or transactional) and rendered as its class requires; a new
 * template file without an {@link EmailContent} record and sample fails here.
 */
class MessageClassesTest {

    static final URI UNSUBSCRIBE = URI.create("http://localhost:8080/api/v1/email/unsubscribe?t=consent");

    @Test
    void everyTemplateFile_hasARecordAndASample_soItsClassIsDeclared() throws IOException {
        var dir = Path.of("src/main/resources/email/templates");
        Set<String> files;
        try (var list = Files.list(dir)) {
            files = list.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".html"))
                    .map(n -> n.substring(0, n.length() - ".html".length()))
                    .filter(n -> !n.equals("layout") && !n.equals("button") && !n.startsWith("page-"))
                    .collect(Collectors.toSet());
        }
        var sampled = EmailContent.samples().values().stream()
                .map(EmailContent::template)
                .collect(Collectors.toSet());

        assertThat(sampled)
                .as("a template file without a sample is unclassified")
                .containsAll(files);
        assertThat(files).as("every sample has its files").containsAll(sampled);
        // every record (sealed: the compiler lists them) has a sample, so every template's purpose is declared
        var records = Arrays.stream(EmailContent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
        var sampledRecords = EmailContent.samples().values().stream()
                .map(c -> c.getClass().getSimpleName())
                .collect(Collectors.toSet());
        assertThat(sampledRecords).containsExactlyInAnyOrderElementsOf(records);
    }

    @Test
    void commercialTemplates_declareTheirConsent_andTransactionalOnesNone() {
        EmailContent.samples().values().forEach(content -> {
            if (MessageClasses.of(content) == MessageClass.COMMERCIAL) {
                assertThat(content.consentCategory()).as(content.template()).isNotNull();
            } else {
                assertThat(content.consentCategory()).as(content.template()).isNull();
            }
        });
        assertThat(MessageClasses.of(EmailContent.samples().get("marketing-offer")))
                .isEqualTo(MessageClass.COMMERCIAL);
        assertThat(MessageClasses.of(EmailContent.samples().get("customer-update")))
                .isEqualTo(MessageClass.TRANSACTIONAL);
    }

    @Test
    void rows_offersIsTheOnlyCommercialRow_andUnknownRowsFailClosed() {
        assertThat(MessageClasses.ROWS.entrySet().stream()
                        .filter(e -> e.getValue() == MessageClass.COMMERCIAL)
                        .map(java.util.Map.Entry::getKey))
                .containsExactly("customer.offers");
        assertThat(MessageClasses.commercialRow("customer", "offers")).isTrue();
        assertThat(MessageClasses.commercialRow("customer", "order_updates")).isFalse();
        assertThat(MessageClasses.commercialRow("customer", "something_new")).isTrue();
        assertThat(ConsentCategory.ofChannel("sms")).isEqualTo(ConsentCategory.MARKETING_SMS);
        assertThat(ConsentCategory.ofCode("marketing_push")).contains(ConsentCategory.MARKETING_PUSH);
    }

    @Test
    void aCommercialEmail_withoutTheSenderOrTheLink_isRefused() {
        var offer = EmailContent.samples().get("marketing-offer");
        var good = EmailTemplatesTest.TEMPLATES.render(offer, Locale.CANADA_FRENCH, UNSUBSCRIBE);

        assertThat(good.text()).contains("Northline Marketplace Inc.", "1200 – 8th Avenue SW", UNSUBSCRIBE.toString());
        assertThatThrownBy(() -> EmailTemplatesTest.TEMPLATES.render(offer, Locale.CANADA, null))
                .hasMessageContaining("unsubscribe");
        var noAddress =
                new RenderedEmail(good.subject(), good.html(), good.text().replace("1200 – 8th Avenue SW", ""));
        assertThatThrownBy(() -> CommercialMessageCheck.email(
                        "marketing-offer",
                        noAddress,
                        "Northline Marketplace Inc.",
                        "Northline Marketplace Inc. · 1200 – 8th Avenue SW, Calgary, Alberta T2P 1B5, Canada",
                        UNSUBSCRIBE))
                .hasMessageContaining("the mailing address");
        var otherName = new EmailTemplates(
                "Other Legal Name Ltd.", "1 Test Street, Testville", "x@example.ca", java.time.ZoneOffset.UTC);
        assertThat(otherName.render(offer, Locale.CANADA, UNSUBSCRIBE).text())
                .contains("Other Legal Name Ltd. · 1 Test Street, Testville", "Other Legal Name Ltd. sent you");
    }

    @Test
    void theSmsCheck_wantsTheLegalNameAndTheOptOutLink() {
        var link = URI.create("http://localhost:8080/api/v1/email/unsubscribe?t=sms");
        assertThat(CommercialMessageCheck.sms(
                        "Northline Marketplace Inc.: offer. Stop: " + link, "Northline Marketplace Inc.", link))
                .contains("Stop");
        assertThatThrownBy(() -> CommercialMessageCheck.sms("Offer!", "Northline Marketplace Inc.", link))
                .isInstanceOf(IllegalStateException.class);
    }
}
