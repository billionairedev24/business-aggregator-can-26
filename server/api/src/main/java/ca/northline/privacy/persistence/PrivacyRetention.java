package ca.northline.privacy.persistence;

import ca.northline.privacy.application.PrivacyWork;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.RetentionContributor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-107, {@code account.closed_profile}: "deleted within 30 days of closure". Closing an account is an erasure (S-105):
 * the pipeline closes the account and erases module by module within minutes. This finds erasures whose account closed
 * more than 30 days ago with a step still pending or failed (a module kept failing, its back-off grew) and runs their
 * steps now, through the same pipeline — there is no second anonymiser. Steps held by a legal hold (an open order, a
 * dispute) are reported as held: what they keep is the other categories' business.
 */
@Slf4j
@Component
class PrivacyRetention implements RetentionContributor {

    static final String CATEGORY = "account.closed_profile";

    /** Erasures past the cutoff with work left: a step pending or failed, or (unless held) a step held. */
    private static final String EXPIRED = """
            from privacy.requests r
             where r.type = 'erasure' and r.state in ('in_progress', 'completed') and r.started_at < :cutoff
               and (exists (select 1 from privacy.erasure_steps s
                             where s.request_id = r.id and s.status in ('pending', 'failed'))
                    or (exists (select 1 from privacy.erasure_steps s where s.request_id = r.id and s.status = 'held')
                        and not ('privacy_request:' || r.id = any(:held))))
            """;

    private final JdbcClient jdbc;
    private final PrivacyWork work;
    private final TransactionTemplate tx;

    PrivacyRetention(JdbcClient jdbc, PrivacyWork work, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.work = work;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String module() {
        return "privacy";
    }

    @Override
    public Set<String> categories() {
        return Set.of(CATEGORY);
    }

    @Override
    public List<HeldRef> holds(Instant now) {
        return jdbc.sql("""
                        select s.request_id, h->>'reason' as reason
                          from privacy.erasure_steps s cross join jsonb_array_elements(s.holds) h
                         where s.status = 'held'
                        """)
                .query((rs, _) -> HeldRef.open(
                        "privacy_request", rs.getString("request_id"), hold(rs.getString("reason"))))
                .list();
    }

    private static Hold hold(String code) {
        return CodedEnum.fromCode(Hold.class, code);
    }

    @Override
    public long expired(Run run) {
        return jdbc.sql("select count(*) " + EXPIRED)
                .params(Map.of("cutoff", run.before(), "held", run.heldKeys()))
                .query(Long.class)
                .single();
    }

    @Override
    public long purge(Run run) {
        var ids = jdbc.sql("select r.id " + EXPIRED + " order by r.started_at limit :batch")
                .params(Map.of("cutoff", run.before(), "held", run.heldKeys(), "batch", run.batch()))
                .query((rs, _) -> rs.getString(1))
                .list();
        var done = 0L;
        for (var id : ids) {
            tx.executeWithoutResult(_ -> jdbc.sql("""
                            update privacy.erasure_steps set next_attempt_at = null, updated_at = now()
                             where request_id = :id and status in ('pending', 'failed')
                            """).param("id", id).update());
            try {
                work.run(id);
                var left = jdbc.sql("""
                                select count(*) from privacy.erasure_steps
                                 where request_id = :id and status in ('pending', 'failed')
                                """)
                        .param("id", id)
                        .query(Long.class)
                        .single();
                done += left == 0 ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Overdue erasure {} still fails ({})", id, e.getClass().getSimpleName());
            }
        }
        return done;
    }
}
