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
import org.junit.jupiter.api.BeforeEach;
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

    /** The clock is shared by every worker test class: start from noon whatever the previous class left. */
    @BeforeEach
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
                .satisfies(t -> assertThat(t.body())
                        .startsWith("Northline : Livraison gratuite ce week-end.")
                        .contains(
                                "— Northline Marketplace Inc. · Désabonnement : http://localhost:8080/api/v1/email/unsubscribe?t=")
                        .endsWith(" ou répondez ARRET")); // S-157: the STOP keyword works too
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
        // the job's batch is global: rows other test classes left due by the morning may come first
        for (var i = 0; i < 100 && deferred(kofi.id()) > 0; i++) {
            notifier.sendDue(50);
        }

        assertThat(pushes.to(kofi.id())).hasSize(1);
        assertThat(texts.to(kofi.phone())).isEmpty();
        assertThat(deferred(kofi.id())).isZero();
    }

    /** S-157: what the console's offer producer queues (the api's OfferCampaignJdbc row and payload). */
    void queued(Member to, String channel, String campaign, Instant due) {
        var payload = new java.util.LinkedHashMap<String, Object>(PersonalNotices.offer(
                to.id(),
                "Free delivery this weekend",
                "Order before Sunday night and delivery is on us.",
                "Livraison gratuite ce week-end",
                "Commandez avant dimanche soir et la livraison est offerte.",
                "/shop"));
        payload.put("campaignId", campaign);
        jdbc.sql("""
                        insert into messaging.deferred_notifications (id, channel, audience, user_id, merchant_id,
                               event_id, event_type, event_version, payload, due_at)
                        values (:id, :channel, 'customer', :user, null, :event, 'messaging.offer', 1,
                               cast(:payload as jsonb), :due)""")
                .param("id", Events.id())
                .param("channel", channel)
                .param("user", to.id())
                .param("event", campaign)
                .param("payload", json.writeValueAsString(payload))
                .param("due", due.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    @Test
    void s157_aQueuedOffer_goesOnItsChannelOnly_andATextDueAtNightWaitsForTheMorning() {
        var efua = customer("fr-CA");
        consent(efua.id(), "marketing_email", "granted");
        consent(efua.id(), "marketing_sms", "granted");
        consent(efua.id(), "marketing_push", "granted");
        clock.set(NIGHT);
        queued(efua, "sms", "C" + Events.id(), NIGHT);
        for (var i = 0; i < 20; i++) {
            notifier.sendDue(50);
        }
        assertThat(texts.to(efua.phone())).isEmpty(); // quiet hours: postponed, not an attempt
        assertThat(deferred(efua.id())).isEqualTo(1);
        assertThat(jdbc.sql("select attempts from messaging.deferred_notifications where user_id = ?")
                        .params(efua.id())
                        .query(Integer.class)
                        .single())
                .isZero();

        clock.set(MORNING);
        for (var i = 0; i < 100 && deferred(efua.id()) > 0; i++) {
            notifier.sendDue(50);
        }
        assertThat(texts.to(efua.phone()))
                .singleElement()
                .satisfies(t -> assertThat(t.body())
                        .startsWith("Northline : Livraison gratuite ce week-end.")
                        .endsWith(" ou répondez ARRET"));
        assertThat(emails.to(efua.email())).isEmpty(); // the campaign's channel only
        assertThat(pushes.to(efua.id())).isEmpty();

        queued(efua, "email", "C" + Events.id(), MORNING);
        for (var i = 0; i < 100 && deferred(efua.id()) > 0; i++) {
            notifier.sendDue(50);
        }
        assertThat(emails.to(efua.email()))
                .singleElement()
                .satisfies(e -> assertThat(e.headers()).containsKey(EmailMessage.LIST_UNSUBSCRIBE));
        assertThat(texts.to(efua.phone())).hasSize(1);
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
        // its own exchange: PushEndToEndTest picks numbers in +1587557 too, and a shared number mixes the recorded
        // texts
        var phone = "+1587558" + String.format("%04d", NUMBERS.incrementAndGet() % 10_000);
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

    static final AtomicLong CONSENT_ORDER = new AtomicLong();

    /**
     * As the api writes them (V300), each a little later than the one before: the newest record is the person's
     * consent, and two records with the same time were ordered by their ids alone.
     */
    void consent(String userId, String category, String action) {
        jdbc.sql("""
                        insert into messaging.consent_records (id, user_id, category, action, at, source, wording_version)
                        values (:id, :u, :c, :a, :at, 'app_settings', :w)""")
                .param("id", Events.id())
                .param("u", userId)
                .param("c", category)
                .param("a", action)
                .param(
                        "at",
                        java.sql.Timestamp.from(
                                clock.instant().minusSeconds(60).plusMillis(CONSENT_ORDER.incrementAndGet())))
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
