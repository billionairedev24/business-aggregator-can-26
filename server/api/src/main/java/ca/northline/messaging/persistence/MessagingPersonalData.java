package ca.northline.messaging.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.shared.Ids;
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
 *
 * <p>S-108: the export includes the consent history; erasure withdraws every consent still granted (source
 * {@code erasure}) and drops the network and browser evidence, but keeps the records themselves — the CASL proof of
 * consent ({@code consent_proof}) — until {@code ConsentRetention.PROOF_PERIOD} after the withdrawal.
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
                        """, p),
                section(
                        jdbc,
                        "messaging.consents",
                        "Your consent to marketing messages",
                        "Vos consentements aux messages publicitaires",
                        """
                        select category, action, at, source, wording_version, language, ip_prefix
                          from messaging.consent_records where user_id = :u order by at, id
                        """,
                        p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        var consentProof = eraseConsents(u);
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
        if (consentProof) {
            outcome = outcome.retaining("messaging.consents", Retention.CONSENT_PROOF);
        }
        if (cases > 0) {
            outcome = outcome.retaining("messaging.cases", Retention.BUSINESS_RECORDS);
        }
        return wroteForBusinesses > 0
                ? outcome.retaining("messaging.businessMessages", Retention.BUSINESS_RECORDS)
                : outcome;
    }

    /**
     * Withdraws every consent still granted (idempotent: an already withdrawn category gets no second row) and drops
     * the evidence; returns whether any proof is kept.
     */
    private boolean eraseConsents(String u) {
        var granted = jdbc.sql("""
                        select category, address_hash
                          from (select distinct on (category) category, action, address_hash
                                  from messaging.consent_records where user_id = :u
                                 order by category, at desc, id desc) l
                         where l.action = 'granted'
                        """)
                .param("u", u)
                .query((rs, _) -> new String[] {rs.getString("category"), rs.getString("address_hash")})
                .list();
        for (var g : granted) {
            jdbc.sql("""
                            insert into messaging.consent_records (id, user_id, category, action, at, source, address_hash)
                            values (:id, :u, :c, 'withdrawn', now(), 'erasure', :h)
                            """)
                    .param("id", Ids.next())
                    .param("u", u)
                    .param("c", g[0])
                    .param("h", g[1])
                    .update();
        }
        jdbc.sql("""
                        update messaging.consent_records set ip_prefix = null, user_agent_hash = null
                         where user_id = :u and (ip_prefix is not null or user_agent_hash is not null)
                        """).param("u", u).update();
        return jdbc.sql("select exists (select 1 from messaging.consent_records where user_id = :u)")
                .param("u", u)
                .query(Boolean.class)
                .single();
    }
}
