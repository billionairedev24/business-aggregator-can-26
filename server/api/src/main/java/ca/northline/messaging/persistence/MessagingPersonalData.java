package ca.northline.messaging.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, messaging: notification settings and inbox, push devices (S-102), messages, help cases and the photos a
 * customer uploaded to them.
 *
 * <p>Erasure: push devices, notification settings, the inbox, held-back notifications and the customer's uploads (rows
 * and objects under {@code messaging/customers/<id>/}) go. What a customer wrote in a conversation is blanked; what a
 * team member wrote stays the business's record without their name. Help cases stay as support records with the
 * person's name, subject and account context removed.
 */
@Component
@RequiredArgsConstructor
class MessagingPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;
    private final AttachmentStorage attachments;

    @Override
    public String module() {
        return "messaging";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(
                        jdbc,
                        "messaging.notificationSettings",
                        "Notification settings",
                        "Paramètres de notification",
                        """
                        select matrix, customer_matrix, quiet_on, quiet_from, quiet_to, notify_lang, marketing,
                               updated_at
                          from messaging.notification_prefs where user_id = :u
                        """,
                        p),
                section(jdbc, "messaging.notifications", "Notifications", "Notifications", """
                        select id, channel, template_key, locale, sent_at, read_at
                          from messaging.notifications where user_id = :u order by sent_at
                        """, p),
                section(
                        jdbc,
                        "messaging.pushDevices",
                        "Devices that get push notifications",
                        "Appareils qui reçoivent des notifications",
                        """
                        select app, platform, locale, app_version, permission, created_at, refreshed_at
                          from messaging.push_devices where user_id = :u
                        """,
                        p),
                section(jdbc, "messaging.messages", "Messages you sent", "Messages que vous avez envoyés", """
                        select m.id, m.thread_id, t.ref_code, m.body, m.attachments, m.at
                          from messaging.messages m join messaging.threads t on t.id = m.thread_id
                         where m.sender_id = :u order by m.at
                        """, p),
                section(jdbc, "messaging.cases", "Help cases", "Demandes d'aide", """
                        select id, number, topic, subject, state, channel, created_at, resolved_at
                          from messaging.tickets where requester_id = :u or opened_by = :u order by created_at
                        """, p),
                section(jdbc, "messaging.uploads", "Photos you uploaded", "Photos téléversées", """
                        select id, file_name, content_type, byte_size, created_at
                          from messaging.customer_uploads where customer_id = :u
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        for (var table : List.of(
                "messaging.push_devices",
                "messaging.notification_prefs",
                "messaging.notifications",
                "messaging.deferred_notifications")) {
            jdbc.sql("delete from " + table + " where user_id = :u")
                    .param("u", u)
                    .update();
        }
        attachments.deleteAll("customers/" + u);
        jdbc.sql("delete from messaging.customer_uploads where customer_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("""
                        update messaging.messages set body = '', attachments = '{}', sender_name = ''
                         where sender_id = :u and sender_role = 'customer'
                           and (body <> '' or sender_name <> '' or cardinality(attachments) > 0)
                        """).param("u", u).update();
        var wroteForBusinesses = jdbc.sql("""
                        update messaging.messages set sender_name = ''
                         where sender_id = :u and coalesce(sender_role, '') <> 'customer'
                           and coalesce(sender_name, '') <> ''
                        """).param("u", u).update();
        jdbc.sql("""
                        update messaging.threads set counterpart_name = ''
                         where counterpart_id = :u and coalesce(counterpart_name, '') <> ''
                        """).param("u", u).update();
        var cases = jdbc.sql("""
                        update messaging.tickets
                           set subject = null, ref_label = null, context = null, updated_at = now()
                         where requester_id = :u
                        """).param("u", u).update();
        var outcome = Erasure.done();
        if (cases > 0) {
            outcome = outcome.retaining("messaging.cases", Retention.BUSINESS_RECORDS);
        }
        return wroteForBusinesses > 0
                ? outcome.retaining("messaging.businessMessages", Retention.BUSINESS_RECORDS)
                : outcome;
    }
}
