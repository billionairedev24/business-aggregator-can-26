package ca.northline.ai.persistence;

import ca.northline.ai.application.AiUsageLog;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code ai.usage} (V150): one row per AI request, no content. */
@Component
@RequiredArgsConstructor
class JdbcAiUsageLog implements AiUsageLog {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    public void record(Entry e) {
        jdbc.sql("""
                        insert into ai.usage (id, feature, person_id, merchant_id, provider, model, prompt, model_calls,
                                              prompt_tokens, completion_tokens, cost_micro_usd, latency_ms, tool_runs,
                                              outcome, created_at)
                        values (:id, :feature, :person, :merchant, :provider, :model, :prompt, :calls, :promptTokens,
                                :completionTokens, :cost, :latency, :toolRuns, :outcome, :at)
                        """)
                .param("id", Ids.next())
                .param("feature", e.feature())
                .param("person", e.personId())
                .param("merchant", e.merchantId())
                .param("provider", e.provider())
                .param("model", e.model())
                .param("prompt", e.prompt())
                .param("calls", e.modelCalls())
                .param("promptTokens", e.promptTokens())
                .param("completionTokens", e.completionTokens())
                .param("cost", e.costMicroUsd())
                .param("latency", e.latencyMs())
                .param("toolRuns", e.toolRuns())
                .param("outcome", e.outcome())
                .param("at", JdbcTimes.ts(clock.instant()))
                .update();
    }
}
