package ca.northline.identity.persistence;

import ca.northline.shared.privacy.RetentionContributor;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-107, identity: {@code identity.sign_ins} — "Login and security logs: 12 months". A sign-in ({@code
 * identity.sessions}: device, network address, city, how) goes 12 months after its last activity (ended, else last
 * seen, else started). Deleting one ends it for good: northline-auth treats a sign-in it can't find as ended, and
 * refresh tokens live 30 days at most.
 */
@Component
@RequiredArgsConstructor
class IdentityRetention implements RetentionContributor {

    static final String SIGN_INS = "identity.sign_ins";

    private static final String EXPIRED = """
            from identity.sessions s
             where coalesce(s.revoked_at, s.last_seen_at, s.created_at) < :cutoff
               and not (s.user_id = any(:subjects))
            """;

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "identity";
    }

    @Override
    public Set<String> categories() {
        return Set.of(SIGN_INS);
    }

    @Override
    public long expired(Run run) {
        return jdbc.sql("select count(*) " + EXPIRED)
                .params(Map.of("cutoff", run.before(), "subjects", run.subjects()))
                .query(Long.class)
                .single();
    }

    @Override
    public long purge(Run run) {
        return jdbc.sql("delete from identity.sessions where id in (select s.id " + EXPIRED + " limit :batch)")
                .params(Map.of("cutoff", run.before(), "subjects", run.subjects(), "batch", run.batch()))
                .update();
    }
}
