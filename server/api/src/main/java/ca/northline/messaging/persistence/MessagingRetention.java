package ca.northline.messaging.persistence;

import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.shared.privacy.RetentionContributor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-107, messaging — "Messages and dispute evidence: 2 years after the transaction, or until a dispute is closed plus 1
 * year, whichever is later".
 *
 * <ul>
 *   <li>{@code messaging.conversations}: a conversation between a customer and a business (or Northline and a
 *       business) two years after its last message goes — messages, attachment rows and the files in object storage
 *       ({@link AttachmentStorage}). What it is about holds it: an order or booking still under way, a dispute open
 *       or decided less than a year ago.
 *   <li>{@code messaging.help_cases}: a help case resolved two years ago loses its conversation (messages and files)
 *       and the person's words (subject, reference label, account context, resolution note); the case number,
 *       topic, dates and refund amounts stay as the support record. Photos customers uploaded go two years after
 *       their upload.
 * </ul>
 */
@Component
@RequiredArgsConstructor
class MessagingRetention implements RetentionContributor {

    static final String CONVERSATIONS = "messaging.conversations";
    static final String HELP_CASES = "messaging.help_cases";

    private static final String THREADS = """
            from messaging.threads t
             where t.kind in ('customer', 'support') and coalesce(t.last_message_at, t.created_at) < :cutoff
               and not (coalesce(t.ref_type, '') || ':' || coalesce(t.ref_id, '') = any(:held))
               and not (coalesce(t.counterpart_id, '') = any(:subjects))
               and not (coalesce(t.participant_ids, '{}') && cast(:subjects as text[]))
            """;
    private static final String CASES = """
            from messaging.tickets k
             where k.state = 'resolved' and k.resolved_at < :cutoff
               and (k.subject is not null or k.ref_label is not null or k.context is not null
                    or k.resolution_note is not null
                    or exists (select 1 from messaging.threads h
                                where h.kind = 'case' and h.ref_type = 'ticket' and h.ref_id = k.id))
               and not (coalesce(k.ref_type, '') || ':' || coalesce(k.ref_id, '') = any(:held))
               and not (coalesce(k.requester_id, '') = any(:subjects))
            """;
    private static final String UPLOADS = """
            from messaging.customer_uploads u
             where u.created_at < :cutoff and not (u.customer_id = any(:subjects))
            """;

    private final JdbcClient jdbc;
    private final AttachmentStorage storage;

    @Override
    public String module() {
        return "messaging";
    }

    @Override
    public Set<String> categories() {
        return Set.of(CONVERSATIONS, HELP_CASES);
    }

    @Override
    public long expired(Run run) {
        var p = params(run);
        return switch (run.category()) {
            case CONVERSATIONS -> count(THREADS, p);
            case HELP_CASES -> count(CASES, p) + count(UPLOADS, p);
            default -> throw new IllegalArgumentException(run.category());
        };
    }

    private long count(String from, Map<String, Object> p) {
        return jdbc.sql("select count(*) " + from).params(p).query(Long.class).single();
    }

    @Override
    public long purge(Run run) {
        var p = params(run);
        return switch (run.category()) {
            case CONVERSATIONS -> {
                var threads = ids("select t.id " + THREADS + " order by coalesce(t.last_message_at, t.created_at)", p);
                deleteThreads(threads);
                yield threads.size();
            }
            case HELP_CASES -> cases(p) + uploads(p);
            default -> throw new IllegalArgumentException(run.category());
        };
    }

    private long cases(Map<String, Object> p) {
        var cases = ids("select k.id " + CASES + " order by k.resolved_at", p);
        if (cases.isEmpty()) {
            return 0;
        }
        var array = cases.toArray(String[]::new);
        deleteThreads(jdbc.sql("""
                        select id from messaging.threads where kind = 'case' and ref_type = 'ticket' and ref_id = any(:k)
                        """)
                .param("k", array)
                .query((rs, _) -> rs.getString(1))
                .list());
        jdbc.sql("""
                        update messaging.support_refund_requests set note = null, decision_note = null
                         where ticket_id = any(:k) and (note is not null or decision_note is not null)
                        """)
                .param("k", array)
                .update();
        jdbc.sql("""
                        update messaging.tickets
                           set subject = null, ref_label = null, context = null, resolution_note = null,
                               updated_at = now()
                         where id = any(:k)
                        """)
                .param("k", array)
                .update();
        return cases.size();
    }

    private long uploads(Map<String, Object> p) {
        var uploads = jdbc.sql("select u.id, u.storage_key " + UPLOADS + " order by u.created_at limit :batch")
                .params(p)
                .query((rs, _) -> Map.entry(rs.getString("id"), rs.getString("storage_key")))
                .list();
        for (var upload : uploads) {
            storage.delete(upload.getValue());
        }
        if (!uploads.isEmpty()) {
            jdbc.sql("delete from messaging.customer_uploads where id = any(:ids)")
                    .param("ids", uploads.stream().map(Map.Entry::getKey).toArray(String[]::new))
                    .update();
        }
        return uploads.size();
    }

    /** Messages, their files (rows and objects) and the threads; a file already gone is fine. */
    private void deleteThreads(List<String> threads) {
        if (threads.isEmpty()) {
            return;
        }
        var array = threads.toArray(String[]::new);
        var files = jdbc.sql("""
                        select a.id, a.storage_key from messaging.attachments a
                         where a.id in (select unnest(m.attachments) from messaging.messages m where m.thread_id = any(:t))
                        """)
                .param("t", array)
                .query((rs, _) -> Map.entry(rs.getString("id"), rs.getString("storage_key")))
                .list();
        for (var file : files) {
            storage.delete(file.getValue());
        }
        if (!files.isEmpty()) {
            jdbc.sql("delete from messaging.attachments where id = any(:ids)")
                    .param("ids", files.stream().map(Map.Entry::getKey).toArray(String[]::new))
                    .update();
        }
        jdbc.sql("delete from messaging.messages where thread_id = any(:t)")
                .param("t", array)
                .update();
        jdbc.sql("delete from messaging.threads where id = any(:t)")
                .param("t", array)
                .update();
    }

    private List<String> ids(String select, Map<String, Object> p) {
        return jdbc.sql(select + " limit :batch").params(p).query((rs, _) -> rs.getString(1)).list();
    }

    private static Map<String, Object> params(Run run) {
        return Map.of(
                "cutoff", run.before(), "held", run.heldKeys(), "subjects", run.subjects(), "batch", run.batch());
    }
}
