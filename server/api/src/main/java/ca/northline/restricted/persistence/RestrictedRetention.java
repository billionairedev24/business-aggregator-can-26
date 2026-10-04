package ca.northline.restricted.persistence;

import ca.northline.shared.privacy.RetentionContributor;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Retention, restricted: {@code restricted.handoff_checks} — the ID checks at handoff are deleted two years after the
 * check (evidence for a liquor or tobacco inspector or a dispute; not named in the Privacy Policy — for counsel). A
 * dispute about the order holds them. The age check itself goes with the account (the erasure pipeline).
 */
@Component
@RequiredArgsConstructor
class RestrictedRetention implements RetentionContributor {

    static final String CHECKS = "restricted.handoff_checks";

    private static final String DUE = """
            from restricted.handoff_checks
             where checked_at < :cutoff and not ('order:' || order_id = any(:held))
            """;

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "restricted";
    }

    @Override
    public Set<String> categories() {
        return Set.of(CHECKS);
    }

    @Override
    public long expired(Run run) {
        return jdbc.sql("select count(*) " + DUE)
                .params(Map.of("cutoff", run.before(), "held", run.heldKeys()))
                .query(Long.class)
                .single();
    }

    @Override
    public long purge(Run run) {
        return jdbc.sql("delete from restricted.handoff_checks where id in (select id " + DUE
                        + " order by checked_at limit :batch)")
                .params(Map.of("cutoff", run.before(), "held", run.heldKeys(), "batch", run.batch()))
                .update();
    }
}
