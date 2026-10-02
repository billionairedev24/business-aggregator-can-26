package ca.northline.worker.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.email.EmailMessage;
import ca.northline.email.MessageClasses;
import ca.northline.worker.support.Events;
import ca.northline.worker.support.NotificationTestBeans;
import ca.northline.worker.support.WorkerIntegrationTest;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-108: a commercial notice (the customer's {@code offers} row) goes only with the person's express consent for the
 * channel — asked at send time, also for what quiet hours held back — names the legal sender and carries the
 * unsubscribe mechanism; every notification row is classified.
 */
class CommercialNoticesTest extends WorkerIntegrationTest {

    /** 23:00 in the platform zone of the tests: inside the default quiet hours (22:00–07:00). */
    static final Instant NIGHT = Instant.parse("2026-10-01T05:00:00Z");

    static final Instant MORNING = Instant.parse("2026-10-01T15:00:00Z");
    static final AtomicLong NUMBERS = new AtomicLong(System.nanoTime() % 1_000_000 + 5_000);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    NotificationTestBeans.MutableClock clock;

    @Autowired
    NotificationTestBeans.Emails emails;

    @Autowired
    NotificationTestBeans.Texts texts;

    @Autowired
    NotificationTestBeans.Pushes pushes;

    @Autowired
    Notifier notifier;

    @Autowired
    PersonalNotices personal;

    @AfterEach
    void noon() {
        clock.set(NotificationTestBeans.NOON_IN_EDMONTON);
    }

    @Test
    void withoutConsent_nothingIsSent_onAnyChannel_whateverTheStoredMatrixSays() {
        var amara = customer("en-CA");
        // the matrix cells of the offers row are not consent: S-59 stored them on by default
        jdbc.sql("""
                        insert into messaging.notification_prefs (user_id, customer_matrix)
                        values (:u, '{"offers": {"push": true, "sms": true, "email": true}}')""").param("u", amara.id()).update();

        notifier.notify(offer(amara));

        assertThat(pushes.to(amara.id())).isEmpty();
        assertThat(texts.to(amara.phone())).isEmpty();
        assertThat(emails.to(amara.email())).isEmpty();
    }

    @Test
    void withConsent_eachChannelGoes_withTheSenderAndTheUnsubscribeMechanism() {
        var amara = customer("fr-CA");
        consent(amara.id(), "marketing_email", "granted");
        consent(amara.id(), "marketing_sms", "granted");
        consent(amara.id(), "marketing_push", "granted");

        notifier.notify(offer(amara));

        assertThat(pushes.to(amara.id())).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Free delivery this weekend"); // "same as app": the installation's language
            assertThat(p.data()).containsOnlyKeys("type").containsEntry("type", "offer");
        });
        assertThat(texts.to(amara.phone()))
                .singleElement()
                .satisfies(
                        t -> assertThat(t.body())
                                .startsWith("Northline : Livraison gratuite ce week-end.")
                                .contains(
                                        "— Northline Marketplace Inc. Désabonnement : http://localhost:8080/api/v1/email/unsubscribe?t="));
        EmailMessage email = emails.to(amara.email()).getFirst();
        assertThat(email.subject()).isEqualTo("Livraison gratuite ce week-end — Northline");
        assertThat(email.headers())
                .containsEntry(EmailMessage.LIST_UNSUBSCRIBE_POST, "List-Unsubscribe=One-Click")
                .containsKey(EmailMessage.LIST_UNSUBSCRIBE);
        assertThat(email.text())
                .contains("Northline Marketplace Inc. vous envoie ce message publicitaire")
                .contains("1200 – 8th Avenue SW")
                .contains("Se désabonner");
        assertThat(email.tag()).isEqualTo("marketing-offer");
    }

    @Test
    void consentIsCheckedAtSendTime_aWithdrawalStopsWhatQuietHoursHeldBack() {
        clock.set(NIGHT);
        var kofi = customer("en-CA");
        consent(kofi.id(), "marketing_sms", "granted");
        consent(kofi.id(), "marketing_push", "granted");

        notifier.notify(offer(kofi));
        assertThat(pushes.to(kofi.id())).isEmpty();
        assertThat(texts.to(kofi.phone())).isEmpty();
        assertThat(deferred(kofi.id())).isEqualTo(2); // push and SMS held until morning; no email consent

        consent(kofi.id(), "marketing_sms", "withdrawn"); // e.g. the opt-out link of an earlier text
        clock.set(MORNING);
        notifier.sendDue(50);

        assertThat(pushes.to(kofi.id())).hasSize(1);
        assertThat(texts.to(kofi.phone())).isEmpty();
        assertThat(deferred(kofi.id())).isZero();
    }

    @Test
    void everyNotificationRow_isClassified() {
        var team = Preferences.Defaults.load(json);
        var customers = Preferences.Defaults.customers(json);
        team.matrix()
                .keySet()
                .forEach(row -> assertThat(MessageClasses.ofRow("team", row))
                        .as("team row " + row)
                        .isPresent());
        customers
                .matrix()
                .keySet()
                .forEach(row -> assertThat(MessageClasses.ofRow("customer", row))
                        .as("customer row " + row)
                        .isPresent());
        assertThat(MessageClasses.OTHER).containsKey("push:courier-run");
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    record Member(String id, String email, String phone) {}

    Notice offer(Member to) {
        var payload = PersonalNotices.offer(
                to.id(),
                "Free delivery this weekend",
                "Order before Sunday night and delivery is on us.",
                "Livraison gratuite ce week-end",
                "Commandez avant dimanche soir et la livraison est offerte.",
                "/shop");
        return personal.of(Events.id(), PersonalNotices.OFFER, 1, json.valueToTree(payload))
                .getFirst();
    }

    Member customer(String locale) {
        var id = "u_" + Events.id();
        var email = id.toLowerCase(Locale.ROOT) + "@example.com";
        var phone = "+1587557" + String.format("%04d", NUMBERS.incrementAndGet() % 10_000);
        jdbc.sql("""
                        insert into identity.users (id, email, phone, first_name, last_name, display_name, locale, status)
                        values (:id, :email, :phone, 'Amara', 'Osei', 'Amara Osei', :locale, 'active')""")
                .param("id", id)
                .param("email", email)
                .param("phone", phone)
                .param("locale", locale)
                .update();
        return new Member(id, email, phone);
    }

    /** As the api writes them (V300). */
    void consent(String userId, String category, String action) {
        jdbc.sql("""
                        insert into messaging.consent_records (id, user_id, category, action, at, source, wording_version)
                        values (:id, :u, :c, :a, :at, 'app_settings', :w)""")
                .param("id", Events.id())
                .param("u", userId)
                .param("c", category)
                .param("a", action)
                .param("at", java.sql.Timestamp.from(clock.instant().minusSeconds(60)))
                .param("w", "granted".equals(action) ? "account.sms.2026-10" : null)
                .update();
    }

    int deferred(String userId) {
        return jdbc.sql("select count(*) from messaging.deferred_notifications where user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }
}
