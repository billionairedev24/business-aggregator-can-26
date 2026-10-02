package ca.northline.worker.events;

import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Platform audit entries for what an operator does from the command line (S-115): DLQ replays and requeued deferred
 * notifications. One {@code developer.audit_log} row per run (append-only, V181), {@code merchant_id} null — the
 * console's audit log viewer (S-96) lists them under {@code events.} / {@code notifications.}. {@code actor} is
 * whatever the operator typed ({@code --actor=}, their staff email or user id) and goes into {@code after} with the
 * reason, the filters and the outcome; {@code actor_id} stays null because no session proves who ran the command.
 */
public final class OperatorAudit {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Who ran it and why (an incident or ticket reference). Both are required for anything that changes state. */
    public record Operator(String actor, String reason) {
        public Operator {
            if (actor.isBlank() || reason.isBlank()) {
                throw new IllegalArgumentException("--actor=<your staff email or id> and --reason=<incident or ticket>"
                        + " are required for a replay (they go to the audit log)");
            }
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public OperatorAudit(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Appends one entry; {@code details} is serialised as the entry's {@code after}. Returns the entry id. */
    public String record(Operator operator, String action, String targetType, String targetId, Object details) {
        var id = UlidCreator.getMonotonicUlid().toString();
        var after = JSON.createObjectNode()
                .put("actor", operator.actor())
                .put("reason", operator.reason())
                .set("details", JSON.valueToTree(details));
        jdbc.sql("""
                        insert into developer.audit_log (id, merchant_id, actor_id, role, action, target_type, target_id,
                                                         after, at)
                        values (:id, null, null, 'operator', :action, :type, :target, cast(:after as jsonb), :at)""")
                .param("id", id)
                .param("action", action)
                .param("type", targetType)
                .param("target", targetId)
                .param("after", JSON.writeValueAsString(after))
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return id;
    }
}
