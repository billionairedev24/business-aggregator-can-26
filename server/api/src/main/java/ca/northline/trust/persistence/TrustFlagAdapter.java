package ca.northline.trust.persistence;

import ca.northline.trust.application.TrustFlagStore;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Open flags in {@code trust.flags}; a second raise for the same target and rule is a no-op (listener retries). */
@Repository
@RequiredArgsConstructor
class TrustFlagAdapter implements TrustFlagStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public void raise(
            String id,
            String targetType,
            String targetId,
            String rule,
            String merchantId,
            String actorId,
            Map<String, String> evidence) {
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, evidence, state, actor_id, merchant_id)
                        select :id, :type, :target, :rule, cast(:evidence as jsonb), 'open', :actor, :m
                         where not exists (select 1 from trust.flags
                                            where target_type = :type and target_id = :target and rule = :rule)
                        """)
                .param("id", id)
                .param("type", targetType)
                .param("target", targetId)
                .param("rule", rule)
                .param("evidence", JSON.writeValueAsString(evidence))
                .param("actor", actorId)
                .param("m", merchantId)
                .update();
    }
}
