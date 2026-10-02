package ca.northline.developer.persistence;

import ca.northline.shared.privacy.RetentionContributor;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-107, developer: {@code developer.audit_log} — kept seven years (V015's "7-year retention"), then deleted. The log is
 * append-only (V181): its trigger lets a transaction delete rows older than seven years only after saying so ({@code
 * northline.audit_retention = on}, transaction-local), which only this does.
 */
@Component
@RequiredArgsConstructor
class DeveloperRetention implements RetentionContributor {

    static final String AUDIT_LOG = "developer.audit_log";

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "developer";
    }

    @Override
    public Set<String> categories() {
        return Set.of(AUDIT_LOG);
    }

    @Override
    public long expired(Run run) {
        return jdbc.sql("select count(*) from developer.audit_log where at < :cutoff")
                .param("cutoff", run.before())
                .query(Long.class)
                .single();
    }

    @Override
    public long purge(Run run) {
        jdbc.sql("select set_config('northline.audit_retention', 'on', true)")
                .query(String.class)
                .single();
        return jdbc.sql("""
                        delete from developer.audit_log
                         where id in (select id from developer.audit_log
                                       where at < :cutoff and at < now() - interval '7 years' order by at limit :batch)
                        """)
                .params(Map.of("cutoff", run.before(), "batch", run.batch()))
                .update();
    }
}
