package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.email.EmailMessage;
import ca.northline.messaging.application.NotificationPreferences.UpdateNotificationMatrix;
import ca.northline.messaging.application.NotificationPreferences.ViewNotificationMatrix;
import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.api.DisputeUpdated;
import ca.northline.payments.api.PayoutAccountChanged;
import ca.northline.payments.api.PayoutSent;
import ca.northline.payments.api.RefundCaseUpdated;
import ca.northline.payments.api.RefundIssued;
import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-13: payments events email the business's team after commit — once per event and member, in the member's language,
 * honouring Settings › Notifications (except the bank-account security notice) — and the unsubscribe link turns the
 * email cell off (CASL).
 */
class MerchantEmailNoticesTest extends IntegrationTest {

    static final Instant AT = Instant.parse("2026-10-02T15:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ViewNotificationMatrix matrix;

    @Autowired
    UpdateNotificationMatrix updateMatrix;

    record Team(String merchantId, String owner, String ownerEmail, String bookkeeperEmail, String techEmail) {}

    Team team() {
        var fx = new SettingsFixtures(jdbc);
        var ownerEmail = SettingsFixtures.email("owner");
        var bookkeeperEmail = SettingsFixtures.email("books");
        var techEmail = SettingsFixtures.email("tech");
        var owner = fx.person("Ravi Sandhu", ownerEmail, null, "passkey");
        var bookkeeper = fx.person("Priya Sandhu", bookkeeperEmail, null, "totp");
        var tech = fx.person("Jas Gill", techEmail, null, "totp");
        jdbc.sql("update identity.users set locale = 'fr-CA' where id = ?")
                .params(bookkeeper)
                .update();
        var merchantId = data.merchant("provider", "Prairie Wrench");
        data.member(merchantId, owner, MerchantRole.OWNER);
        data.member(merchantId, bookkeeper, MerchantRole.BOOKKEEPER);
        data.member(merchantId, tech, MerchantRole.TECHNICIAN);
        return new Team(merchantId, owner, ownerEmail, bookkeeperEmail, techEmail);
    }

    void publish(Object event) {
        tx.executeWithoutResult(_ -> publisher.publishEvent(event));
    }

    List<EmailMessage> awaitOne(String address) {
        await().atMost(Duration.ofSeconds(10)).until(() -> !emails.to(address).isEmpty());
        return emails.to(address);
    }

    static void settle() {
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until(() -> true);
    }

    @Test
    void payoutSent_emailsOwnerAndBookkeeperOnce_inTheirLanguage() {
        var t = team();
        var event = new PayoutSent(Ids.next(), AT, Ids.next(), t.merchantId(), "instant", 82_260, 823, AT);

        publish(event);
        publish(event); // delivered twice (e.g. resubmitted): still one email per member

        assertThat(awaitOne(t.ownerEmail()).getFirst().subject())
                .isEqualTo("$814.37 is on its way to your bank — Prairie Wrench");
        assertThat(awaitOne(t.bookkeeperEmail()).getFirst().subject())
                .isEqualTo("814,37 $ est en route vers votre banque — Prairie Wrench");
        settle();
        assertThat(emails.to(t.ownerEmail())).hasSize(1);
        assertThat(emails.to(t.bookkeeperEmail())).hasSize(1);
        assertThat(emails.to(t.techEmail())).isEmpty();
        var mail = emails.to(t.ownerEmail()).getFirst();
        assertThat(mail.headers()).containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        assertThat(mail.headers().get("List-Unsubscribe"))
                .startsWith("<http://localhost:8080/api/v1/email/unsubscribe?t=");
        assertThat(mail.text()).contains("http://localhost:3100/b/" + t.merchantId() + "/payouts");
    }

    @Test
    void theMatrix_isHonoured_butTheBankChangeSecurityNoticeAlwaysGoesOut() {
        var t = team();
        updateMatrix.update(t.owner(), Map.of("payout", Map.of("email", false)));

        publish(new PayoutSent(Ids.next(), AT, Ids.next(), t.merchantId(), "scheduled", 10_000, 0, AT));
        publish(new PayoutAccountChanged(
                Ids.next(), AT, Ids.next(), t.merchantId(), "requested", AT.plusSeconds(86_400)));

        var notice = awaitOne(t.ownerEmail());
        assertThat(notice).singleElement().satisfies(m -> {
            assertThat(m.tag()).isEqualTo("bank-account-change");
            assertThat(m.subject()).isEqualTo("Your payout bank account is changing — Prairie Wrench");
            assertThat(m.headers()).isEmpty(); // transactional: no unsubscribe
            assertThat(m.text()).contains("Didn’t make this change?");
        });
        settle();
        assertThat(emails.to(t.ownerEmail())).extracting(EmailMessage::tag).containsExactly("bank-account-change");
        assertThat(emails.to(t.bookkeeperEmail())).extracting(EmailMessage::tag).containsExactly("payout-sent");
    }

    @Test
    void disputesAndRefunds_emailTheOwners() {
        var t = team();

        publish(new DisputeUpdated(
                Ids.next(), AT, Ids.next(), t.merchantId(), "DS-9001", "opened", 38_900, AT.plusSeconds(259_200)));
        publish(new DisputeDecided(
                Ids.next(), AT, Ids.next(), t.merchantId(), "DS-9001", 38_900, "esc", "partial", 19_450, "agent", null));
        publish(new RefundCaseUpdated(
                Ids.next(), AT, Ids.next(), t.merchantId(), "RF-9002", "requested", 4_500, AT.plusSeconds(86_400)));
        publish(new RefundIssued(Ids.next(), AT, Ids.next(), t.merchantId(), "RF-9002", null, 4_500, "merchant"));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> emails.to(t.ownerEmail()).size() == 4);
        assertThat(emails.to(t.ownerEmail()))
                .extracting(EmailMessage::subject)
                .containsExactlyInAnyOrder(
                        "New dispute DS-9001 — respond soon (Prairie Wrench)",
                        "Dispute DS-9001 has been decided (Prairie Wrench)",
                        "Refund request RF-9002 for $45.00 — Prairie Wrench",
                        "Refund RF-9002 was paid — Prairie Wrench");
        settle();
        assertThat(emails.to(t.bookkeeperEmail())).isEmpty();
        assertThat(emails.to(t.techEmail())).isEmpty();
    }

    @Test
    void theUnsubscribeLink_confirmsThenTurnsThatEmailOff() throws Exception {
        var t = team();
        publish(new DisputeUpdated(
                Ids.next(), AT, Ids.next(), t.merchantId(), "DS-9101", "offer_declined", 12_000, null));
        var header = awaitOne(t.ownerEmail()).getFirst().headers().get("List-Unsubscribe");
        var url = URI.create(header.substring(1, header.length() - 1));
        var token = UriComponentsBuilder.fromUri(url).build().getQueryParams().getFirst("t");

        mvc.perform(get(url.getPath()).param("t", token)) // no session: the token is the authorisation
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.containsString("Stop emails about “Disputes &amp; refunds”?")));
        assertThat(matrix.view(t.owner()).wants("dispute", "email")).isTrue(); // GET changes nothing

        mvc.perform(post(url.getPath()) // RFC 8058 one-click
                        .param("t", token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("You’re unsubscribed")));
        assertThat(matrix.view(t.owner()).wants("dispute", "email")).isFalse();
        assertThat(matrix.view(t.owner()).wants("payout", "email")).isTrue();

        publish(new DisputeUpdated(
                Ids.next(), AT, Ids.next(), t.merchantId(), "DS-9102", "offer_expired", 12_000, null));
        settle();
        assertThat(emails.to(t.ownerEmail())).hasSize(1);
    }

    @Test
    void aTamperedUnsubscribeToken_isRefused() throws Exception {
        mvc.perform(post("/api/v1/email/unsubscribe").param("t", "djF8dXNlcnxkaXNwdXRlfGVuLUNB.forged"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("isn’t valid")));
        mvc.perform(get("/api/v1/email/unsubscribe")).andExpect(status().isBadRequest());
    }
}
