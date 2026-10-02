package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S-105: messaging's part — push devices (S-102), notification settings and inbox and the customer's uploads (rows and
 * objects) go; what a customer wrote is blanked; what a team member wrote stays the business's, without the name.
 */
class MessagingPersonalDataTest extends PersonalDataTest {

    @Autowired
    AttachmentStorage attachments;

    @Override
    protected String module() {
        return "messaging";
    }

    @Test
    void erasureRemovesDevicesSettingsInboxAndUploadsAndBlanksWhatTheCustomerWrote() {
        var person = person();
        var u = person.userId();
        var p = Map.of("u", u);
        var upload = "customers/" + u + "/" + Ids.next() + ".png";
        attachments.put(upload, new byte[] {1, 2, 3}, "image/png");
        jdbc.sql("""
                        insert into messaging.customer_uploads (id, customer_id, storage_key, file_name, content_type,
                                                                byte_size)
                        values (:id, :u, :key, 'scratch.png', 'image/png', 3)
                        """).param("id", Ids.next()).param("u", u).param("key", upload).update();
        jdbc.sql("""
                        insert into messaging.push_devices (id, user_id, app, installation_id, platform, token, locale,
                                                            app_version, permission)
                        values (:id, :u, 'consumer', :inst, 'ios', :token, 'en-CA', '1.0.0', 'granted')
                        """)
                .param("id", Ids.next())
                .param("u", u)
                .param("inst", "inst" + Ids.next())
                .param("token", "apns-" + Ids.next())
                .update();
        jdbc.sql("insert into messaging.notification_prefs (user_id, quiet_on) values (:u, true)")
                .params(p)
                .update();
        jdbc.sql(
                        "insert into messaging.notifications (id, user_id, channel, template_key) values (:id, :u, 'push', 'x')")
                .param("id", Ids.next())
                .param("u", u)
                .update();
        var thread = Ids.next();
        var team = person();
        jdbc.sql("""
                        insert into messaging.threads (id, kind, merchant_id, counterpart_id, counterpart_name, subject)
                        values (:t, 'customer', 'm1', :u, 'Dana Kowalski', 'Brake job')
                        """).param("t", thread).param("u", u).update();
        jdbc.sql("""
                        insert into messaging.messages (id, thread_id, sender_id, sender_role, sender_name, body, at)
                        values (:a, :t, :u, 'customer', 'Dana', 'I live at 12 Elm St', now()),
                               (:b, :t, :team, 'merchant', 'Ravi', 'See you Tuesday', now())
                        """)
                .param("a", Ids.next())
                .param("b", Ids.next())
                .param("t", thread)
                .param("u", u)
                .param("team", team.userId())
                .update();
        jdbc.sql("""
                        insert into messaging.tickets (id, requester_type, requester_id, topic, state, subject, ref_label,
                                                       context)
                        values (:id, 'customer', :u, 'refund', 'resolved', 'Refund for Dana', 'Order · D. Kowalski',
                                '{"phone": "+14035550148"}')
                        """).param("id", Ids.next()).param("u", u).update();

        assertThat(records(person, "messaging.pushDevices")).isEqualTo(1);
        assertThat(records(person, "messaging.uploads")).isEqualTo(1);
        assertThat(section(person, "messaging.messages").orElseThrow().json()).contains("12 Elm St");
        assertThat(records(person, "messaging.cases")).isEqualTo(1);

        var outcome = erase(person);

        for (var table : new String[] {"push_devices", "notification_prefs", "notifications"}) {
            assertThat(count("select count(*) from messaging." + table + " where user_id = :u", p))
                    .as(table)
                    .isZero();
        }
        assertThat(count("select count(*) from messaging.customer_uploads where customer_id = :u", p))
                .isZero();
        assertThat(attachments.get(upload)).isEmpty();
        assertThat(text("select body || sender_name from messaging.messages where sender_id = :u", p))
                .isEmpty();
        assertThat(text(
                        "select body || '/' || sender_name from messaging.messages where sender_id = :t",
                        Map.of("t", team.userId())))
                .isEqualTo("See you Tuesday/Ravi");
        assertThat(text("select counterpart_name from messaging.threads where id = :t", Map.of("t", thread)))
                .isEmpty();
        assertThat(text(
                        "select concat_ws('|', subject, ref_label, context::text, state) from messaging.tickets "
                                + "where requester_id = :u",
                        p))
                .isEqualTo("resolved");
        assertThat(outcome.retained()).containsExactly(new Kept<>("messaging.cases", Retention.BUSINESS_RECORDS));
        assertIdempotent(person, outcome);

        var teamOutcome = erase(team);
        assertThat(text(
                        "select body || '/' || sender_name from messaging.messages where sender_id = :t",
                        Map.of("t", team.userId())))
                .isEqualTo("See you Tuesday/");
        assertThat(teamOutcome.retained())
                .containsExactly(new Kept<>("messaging.businessMessages", Retention.BUSINESS_RECORDS));
    }

    /** S-108: the consent history is exported; erasure withdraws what is granted and keeps the minimal proof. */
    @Test
    void consents_areExported_andErasureWithdrawsThem_keepingTheProofWithoutTheEvidence() {
        var person = person();
        var u = person.userId();
        var p = Map.of("u", u);
        jdbc.sql("""
                        insert into messaging.consent_records (id, user_id, category, action, at, source, wording_version,
                                                               language, address_hash, ip_prefix, user_agent_hash)
                        values (:a, :u, 'marketing_email', 'granted', now() - interval '2 days', 'web_settings',
                                'account.email.2026-10', 'en', :h, '203.0.113.0/24', :ua),
                               (:b, :u, 'marketing_sms', 'granted', now() - interval '2 days', 'app_settings',
                                'account.sms.2026-10', 'en', null, '203.0.113.0/24', :ua),
                               (:c, :u, 'marketing_sms', 'withdrawn', now() - interval '1 day', 'unsubscribe_link',
                                null, 'en', null, null, null)
                        """)
                .param("a", Ids.next())
                .param("b", Ids.next())
                .param("c", Ids.next())
                .param("u", u)
                .param("h", "a".repeat(64))
                .param("ua", "b".repeat(64))
                .update();

        assertThat(records(person, "messaging.consents")).isEqualTo(3);
        assertThat(section(person, "messaging.consents").orElseThrow().json())
                .contains("account.email.2026-10", "unsubscribe_link")
                .doesNotContain("bbbbbbbb"); // the user-agent hash isn't the person's data to read back

        var outcome = erase(person);

        assertThat(count("""
                        select count(*) from messaging.consent_records
                         where user_id = :u and category = 'marketing_email' and action = 'withdrawn'
                           and source = 'erasure' and address_hash is not null""", p)).isEqualTo(1);
        assertThat(count("select count(*) from messaging.consent_records where user_id = :u", p))
                .isEqualTo(4); // no second withdrawal of the SMS consent
        assertThat(count("""
                        select count(*) from messaging.consent_records
                         where user_id = :u and (ip_prefix is not null or user_agent_hash is not null)""", p)).isZero();
        assertThat(outcome.retained()).contains(new Kept<>("messaging.consents", Retention.CONSENT_PROOF));
        assertIdempotent(person, outcome);
    }
}
