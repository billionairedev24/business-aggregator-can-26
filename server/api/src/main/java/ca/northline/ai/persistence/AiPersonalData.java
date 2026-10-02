package ca.northline.ai.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, ai: the usage records of the person's AI requests (feature, model, tokens, outcome — never the prompt or the
 * answer, which are not stored). They go on erasure; the budget counters they fed are aggregates.
 */
@Component
@RequiredArgsConstructor
class AiPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "ai";
    }

    @Override
    public List<Section> export(Subject subject) {
        return List.of(section(
                jdbc,
                "ai.usage",
                "AI assistant requests",
                "Requêtes à l'assistant IA",
                """
                        select feature, merchant_id, provider, model, prompt, prompt_tokens, completion_tokens, outcome,
                               created_at
                          from ai.usage where person_id = :u order by created_at
                        """,
                Map.of("u", subject.userId())));
    }

    @Override
    public Erasure erase(Subject subject) {
        jdbc.sql("delete from ai.usage where person_id = :u")
                .param("u", subject.userId())
                .update();
        return Erasure.done();
    }
}
