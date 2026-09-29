package ca.northline.messaging.persistence;

import static ca.northline.messaging.persistence.MessagingSql.JSON;
import static ca.northline.messaging.persistence.MessagingSql.instant;
import static ca.northline.messaging.persistence.MessagingSql.requiredInstant;
import static ca.northline.messaging.persistence.MessagingSql.ts;

import ca.northline.messaging.application.BrowseInbox.Attachment;
import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.application.BrowseInbox.QuickReply;
import ca.northline.messaging.application.BrowseInbox.ThreadSummary;
import ca.northline.messaging.application.ThreadStore;
import ca.northline.messaging.domain.InboxScope;
import ca.northline.messaging.domain.Portal;
import ca.northline.messaging.domain.SenderRole;
import ca.northline.messaging.domain.ThreadKind;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Threads, messages and quick replies over {@code messaging.threads / messages / macros}. */
@Repository
@RequiredArgsConstructor
class MessagingThreadAdapter implements ThreadStore {

    /** Customer + support threads the scope allows (case threads live under Help). */
    private static final String SUMMARY = """
            select t.id, t.kind, t.counterpart_name, t.subject, t.ref_type, t.ref_id, t.ref_code, t.assignee_id,
                   t.last_message_at, lm.body as last_body,
                   exists (select 1 from messaging.messages m
                            where m.thread_id = t.id and m.sender_role <> 'merchant'
                              and m.at > coalesce(t.merchant_read_at, '-infinity')) as unread
              from messaging.threads t
              left join lateral (select m.body from messaging.messages m where m.thread_id = t.id
                                  order by m.at desc, m.id desc limit 1) lm on true
             where t.merchant_id = :m and t.kind in ('customer', 'support')
               and (:scope = 'all'
                    or (:scope = 'assigned' and t.kind = 'customer' and t.assignee_id = :user)
                    or (:scope = 'customers' and t.kind = 'customer'))
            """;

    private final JdbcClient jdbc;

    @Override
    public List<ThreadSummary> list(String merchantId, InboxScope scope, String userId) {
        if (scope == InboxScope.NONE) {
            return List.of();
        }
        return jdbc.sql(SUMMARY + " order by t.last_message_at desc nulls last, t.id desc")
                .param("m", merchantId)
                .param("scope", scope.name().toLowerCase(Locale.ROOT))
                .param("user", userId)
                .query((rs, _) -> summary(rs))
                .list();
    }

    @Override
    public Optional<ThreadSummary> find(String merchantId, String threadId, InboxScope scope, String userId) {
        if (scope == InboxScope.NONE) {
            return Optional.empty();
        }
        return jdbc.sql(SUMMARY + " and t.id = :id")
                .param("m", merchantId)
                .param("scope", scope.name().toLowerCase(Locale.ROOT))
                .param("user", userId)
                .param("id", threadId)
                .query((rs, _) -> summary(rs))
                .optional();
    }

    @Override
    public Optional<String> findByRef(String merchantId, String refType, String refId) {
        return jdbc.sql("""
                        select id from messaging.threads
                         where merchant_id = :m and ref_type = :type and ref_id = :ref
                         order by created_at, id limit 1
                        """)
                .param("m", merchantId)
                .param("type", refType)
                .param("ref", refId)
                .query(String.class)
                .optional();
    }

    @Override
    public void create(NewThread t) {
        jdbc.sql("""
                        insert into messaging.threads (id, merchant_id, kind, ref_type, ref_id, ref_code, counterpart_id,
                                                       counterpart_name, subject, assignee_id, participant_ids, created_at)
                        values (:id, :m, :kind, :refType, :refId, :refCode, :cpId, :cpName, :subject, :assignee,
                                :participants, :at)
                        """)
                .param("id", t.id())
                .param("m", t.merchantId())
                .param("kind", t.kind().code())
                .param("refType", t.refType())
                .param("refId", t.refId())
                .param("refCode", t.refCode())
                .param("cpId", t.counterpartId())
                .param("cpName", t.counterpartName())
                .param("subject", t.subject())
                .param("assignee", t.assigneeId())
                .param("participants", t.counterpartId() == null ? new String[0] : new String[] {t.counterpartId()})
                .param("at", ts(t.createdAt()))
                .update();
    }

    @Override
    public List<Message> messages(String threadId) {
        return jdbc.sql("""
                        select m.id, m.sender_role, m.sender_name, m.body, m.at, coalesce(m.flagged, false) as flagged,
                               m.template_key,
                               coalesce((select jsonb_agg(jsonb_build_object('id', a.id, 'fileName', a.file_name,
                                                          'contentType', a.content_type, 'byteSize', a.byte_size)
                                                          order by array_position(m.attachments, a.id))
                                           from messaging.attachments a where a.id = any(m.attachments)), '[]') as files
                          from messaging.messages m
                         where m.thread_id = :t
                         order by m.at, m.id
                        """)
                .param("t", threadId)
                .query((rs, _) -> new Message(
                        rs.getString("id"),
                        CodedEnum.fromCode(SenderRole.class, rs.getString("sender_role")),
                        rs.getString("sender_name"),
                        rs.getString("body"),
                        files(rs.getString("files")),
                        requiredInstant(rs, "at"),
                        rs.getBoolean("flagged"),
                        rs.getString("template_key")))
                .list();
    }

    @Override
    public void add(NewMessage m) {
        jdbc.sql("""
                        insert into messaging.messages (id, thread_id, sender_id, sender_role, sender_name, body,
                                                        attachments, template_key, at, flagged)
                        values (:id, :t, :sender, :role, :name, :body, :files, :template, :at, :flagged)
                        """)
                .param("id", m.id())
                .param("t", m.threadId())
                .param("sender", m.senderId())
                .param("role", m.senderRole().code())
                .param("name", m.senderName())
                .param("body", m.body())
                .param("files", m.attachmentIds().toArray(String[]::new))
                .param("template", m.templateKey())
                .param("at", ts(m.at()))
                .param("flagged", m.flagged())
                .update();
        jdbc.sql("""
                        update messaging.threads
                           set last_message_at = greatest(coalesce(last_message_at, :at), :at),
                               merchant_read_at = case when :role = 'merchant'
                                                       then greatest(coalesce(merchant_read_at, :at), :at)
                                                       else merchant_read_at end
                         where id = :t
                        """)
                .param("t", m.threadId())
                .param("at", ts(m.at()))
                .param("role", m.senderRole().code())
                .update();
    }

    @Override
    public void markRead(String threadId, Instant at) {
        jdbc.sql("""
                        update messaging.threads set merchant_read_at = greatest(coalesce(merchant_read_at, :at), :at)
                         where id = :t
                        """).param("t", threadId).param("at", ts(at)).update();
    }

    @Override
    public int unreadThreads(String merchantId, InboxScope scope, String userId) {
        if (scope == InboxScope.NONE) {
            return 0;
        }
        return jdbc.sql("select count(*) from (" + SUMMARY + ") s where s.unread")
                .param("m", merchantId)
                .param("scope", scope.name().toLowerCase(Locale.ROOT))
                .param("user", userId)
                .query(Integer.class)
                .single();
    }

    @Override
    public List<LinkedRecord> linkedRecords(String merchantId, int limit) {
        return jdbc.sql("""
                        select ref_type, ref_id, ref_code, counterpart_name from (
                          select distinct on (t.ref_type, t.ref_id) t.ref_type, t.ref_id, t.ref_code,
                                 t.counterpart_name, t.last_message_at
                            from messaging.threads t
                           where t.merchant_id = :m and t.kind in ('customer', 'support')
                             and t.ref_type in ('booking', 'order', 'dispute') and t.ref_id is not null
                           order by t.ref_type, t.ref_id, t.last_message_at desc nulls last
                        ) r
                         order by r.last_message_at desc nulls last, r.ref_id
                         limit :limit
                        """)
                .param("m", merchantId)
                .param("limit", limit)
                .query((rs, _) -> new LinkedRecord(
                        rs.getString("ref_type"),
                        rs.getString("ref_id"),
                        rs.getString("ref_code"),
                        rs.getString("counterpart_name")))
                .list();
    }

    @Override
    public List<QuickReply> quickReplies(Portal portal, String lang) {
        return jdbc.sql("""
                        select key, coalesce(body_i18n ->> :lang, body_i18n ->> 'en') as text
                          from messaging.macros
                         where topic = 'quick_reply' and :portal = any(portals)
                         order by position, key
                        """)
                .param("lang", lang)
                .param("portal", portal.code())
                .query((rs, _) -> new QuickReply(rs.getString("key"), rs.getString("text")))
                .list();
    }

    @Override
    public Optional<String> quickReplyText(Portal portal, String key, String lang) {
        return jdbc.sql("""
                        select coalesce(body_i18n ->> :lang, body_i18n ->> 'en') from messaging.macros
                         where topic = 'quick_reply' and key = :key and :portal = any(portals)
                        """)
                .param("lang", lang)
                .param("key", key)
                .param("portal", portal.code())
                .query(String.class)
                .optional();
    }

    private static ThreadSummary summary(ResultSet rs) throws SQLException {
        return new ThreadSummary(
                rs.getString("id"),
                CodedEnum.fromCode(ThreadKind.class, rs.getString("kind")),
                rs.getString("counterpart_name"),
                rs.getString("subject"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("ref_code"),
                rs.getString("assignee_id"),
                instant(rs, "last_message_at"),
                rs.getString("last_body"),
                rs.getBoolean("unread"));
    }

    private static List<Attachment> files(@Nullable String json) {
        return json == null
                ? List.of()
                : JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, Attachment.class));
    }
}
