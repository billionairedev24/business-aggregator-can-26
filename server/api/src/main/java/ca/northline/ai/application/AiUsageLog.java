package ca.northline.ai.application;

import org.jspecify.annotations.Nullable;

/** Outbound port: one row per AI request in {@code ai.usage} — who, which feature/model/prompt, tokens, cost. Never content. */
public interface AiUsageLog {

    /**
     * @param outcome {@code ok}, {@code error}, {@code rate_limited}, {@code unavailable}
     */
    record Entry(
            String feature,
            String personId,
            @Nullable String merchantId,
            String provider,
            String model,
            String prompt,
            int modelCalls,
            long promptTokens,
            long completionTokens,
            @Nullable Long costMicroUsd,
            long latencyMs,
            int toolRuns,
            String outcome) {}

    void record(Entry entry);
}
