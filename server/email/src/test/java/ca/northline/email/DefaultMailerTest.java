package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Once per key, one-click unsubscribe headers for notifications, rejections remembered, outages rethrown. */
class DefaultMailerTest {

    final List<EmailMessage> sent = new ArrayList<>();
    final Set<String> keys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final SentEmails memory = new SentEmails() {
        @Override
        public boolean claim(String key) {
            return keys.add(key);
        }

        @Override
        public void release(String key) {
            keys.remove(key);
        }
    };
    final URI unsubscribe = URI.create("http://localhost:8080/api/v1/email/unsubscribe?t=x");

    Mailer mailer(EmailSender sender) {
        return new DefaultMailer(sender, EmailTemplatesTest.TEMPLATES, memory);
    }

    @Test
    void sendsOncePerKey_withUnsubscribeHeaders() {
        var mailer = mailer(sent::add);
        var delivery = new Mailer.Delivery(
                "evt-1:user-1",
                EmailAddress.of("owner@example.com"),
                EmailContent.samples().get("payout-sent"),
                Locale.CANADA_FRENCH,
                unsubscribe);

        assertThat(mailer.send(delivery)).isEqualTo(Mailer.Outcome.SENT);
        assertThat(mailer.send(delivery)).isEqualTo(Mailer.Outcome.ALREADY_SENT);

        assertThat(sent).singleElement().satisfies(m -> {
            assertThat(m.subject()).contains("est en route vers votre banque");
            assertThat(m.headers())
                    .containsEntry(EmailMessage.LIST_UNSUBSCRIBE, "<" + unsubscribe + ">")
                    .containsEntry(EmailMessage.LIST_UNSUBSCRIBE_POST, "List-Unsubscribe=One-Click");
            assertThat(m.tag()).isEqualTo("payout-sent");
        });
    }

    @Test
    void transactionalEmails_haveNoUnsubscribeHeaders() {
        var mailer = mailer(sent::add);

        mailer.send(new Mailer.Delivery(
                "evt-2:user-1",
                EmailAddress.of("owner@example.com"),
                EmailContent.samples().get("bank-account-change.requested"),
                Locale.CANADA,
                unsubscribe));

        assertThat(sent.getFirst().headers()).isEmpty();
        assertThat(sent.getFirst().text()).doesNotContain(unsubscribe.toString());
    }

    @Test
    void rejections_areRememberedAndNotRetried() {
        var attempts = new int[1];
        var mailer = mailer(_ -> {
            attempts[0]++;
            throw EmailDeliveryFailed.rejected("550 mailbox unavailable");
        });
        var delivery = new Mailer.Delivery(
                "evt-3:user-1",
                EmailAddress.of("gone@example.com"),
                EmailContent.samples().get("team-invitation"),
                Locale.CANADA,
                null);

        assertThat(mailer.send(delivery)).isEqualTo(Mailer.Outcome.REJECTED);
        assertThat(mailer.send(delivery)).isEqualTo(Mailer.Outcome.ALREADY_SENT);
        assertThat(attempts[0]).isEqualTo(1);
    }

    @Test
    void outages_areRethrown_andNotRemembered() {
        var mailer = mailer(_ -> {
            throw EmailDeliveryFailed.unavailable("503", new RuntimeException());
        });
        var delivery = new Mailer.Delivery(
                "evt-4:user-1",
                EmailAddress.of("owner@example.com"),
                EmailContent.samples().get("team-invitation"),
                Locale.CANADA,
                null);

        assertThatThrownBy(() -> mailer.send(delivery)).isInstanceOf(EmailDeliveryFailed.class);
        assertThat(keys).isEmpty();
    }

    @Test
    void commercialEmails_goOnlyWithConsent_askedAtSendTime() {
        var consented = new java.util.HashSet<String>();
        var mailer = new DefaultMailer(
                sent::add,
                EmailTemplatesTest.TEMPLATES,
                memory,
                (user, category) ->
                        category == MessageClasses.ConsentCategory.MARKETING_EMAIL && consented.contains(user));
        var offer = EmailContent.samples().get("marketing-offer");
        var delivery = new Mailer.Delivery(
                "offer-1:user-9", EmailAddress.of("amara@example.com"), offer, Locale.CANADA, unsubscribe, "user-9");

        assertThat(mailer.send(delivery)).isEqualTo(Mailer.Outcome.NO_CONSENT);
        assertThat(sent).isEmpty();
        assertThat(keys).isEmpty(); // not claimed: sent once consent exists

        consented.add("user-9");
        assertThat(mailer.send(delivery)).isEqualTo(Mailer.Outcome.SENT);
        assertThat(sent.getFirst().headers())
                .containsEntry(EmailMessage.LIST_UNSUBSCRIBE, "<" + unsubscribe + ">")
                .containsEntry(EmailMessage.LIST_UNSUBSCRIBE_POST, "List-Unsubscribe=One-Click");
        assertThat(sent.getFirst().text()).contains("Northline Marketplace Inc.", "1200 – 8th Avenue SW");
    }

    @Test
    void commercialEmails_needTheRecipientsId_andNothingIsSentWithoutAConsentSource() {
        var offer = EmailContent.samples().get("marketing-offer");
        var anonymous = new Mailer.Delivery(
                "offer-2:user-9", EmailAddress.of("amara@example.com"), offer, Locale.CANADA, unsubscribe);

        assertThatThrownBy(() -> mailer(sent::add).send(anonymous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipient's id");
        // the library's default (no CommercialConsent bean): nothing commercial goes
        assertThat(mailer(sent::add)
                        .send(new Mailer.Delivery(
                                "offer-3:user-9",
                                EmailAddress.of("amara@example.com"),
                                offer,
                                Locale.CANADA,
                                unsubscribe,
                                "user-9")))
                .isEqualTo(Mailer.Outcome.NO_CONSENT);
        assertThat(sent).isEmpty();
    }

    @Test
    void logsMaskAddresses() {
        assertThat(DefaultMailer.masked(EmailAddress.of("ravi@prairiewrench.ca")))
                .isEqualTo("r***@prairiewrench.ca");
    }
}
