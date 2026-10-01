package ca.northline.ai.application;

import ca.northline.ai.api.AiFeature;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.ai.*} (application.yml; every variable in docs/runbooks/ai.md).
 *
 * @param provider {@code fake} (default; refused under staging/prod) or {@code openrouter} — {@code AI_PROVIDER}
 * @param maxToolRounds tool rounds per question before the assistant must answer — {@code AI_MAX_TOOL_ROUNDS}
 * @param models per-feature model overrides by {@link AiFeature#code()} in kebab case ({@code listing-copy}) ({@code OPENROUTER_MODEL_<FEATURE>}); blank =
 *     the tier's default
 */
@ConfigurationProperties("northline.ai")
public record AiProperties(
        @DefaultValue("fake") Provider provider,
        @DefaultValue("4") int maxToolRounds,
        @DefaultValue Budget budget,
        @DefaultValue Fake fake,
        @DefaultValue OpenRouter openrouter,
        @Nullable Map<String, String> models) {

    public enum Provider {
        FAKE,
        OPENROUTER
    }

    /**
     * @param personTokensPerDay tokens one person may use per (UTC) day — {@code AI_BUDGET_PERSON_TOKENS_PER_DAY}
     * @param merchantTokensPerDay tokens one business may use per day, all its team — {@code AI_BUDGET_MERCHANT_TOKENS_PER_DAY}
     * @param personRequestsPerMinute model requests one person may start per minute — {@code AI_REQUESTS_PER_MINUTE}
     * @param systemTokensPerDay tokens each platform job (trust &amp; safety screening, anomaly scans) may use per day;
     *     jobs have no per-minute rate — {@code AI_BUDGET_SYSTEM_TOKENS_PER_DAY}
     */
    public record Budget(
            @DefaultValue("200000") long personTokensPerDay,
            @DefaultValue("1000000") long merchantTokensPerDay,
            @DefaultValue("20") int personRequestsPerMinute,
            @DefaultValue("2000000") long systemTokensPerDay) {}

    /**
     * {@code provider=fake}: a latency ({@code "0"} or {@code "min-max"} ms) and a cost per million tokens, so local
     * dashboards look like a real model's. Both off by default.
     */
    public record Fake(
            @DefaultValue("0") String latencyMs,
            @DefaultValue("0") double usdPerMillionTokens) {}

    /**
     * OpenRouter (docs/runbooks/ai.md).
     *
     * @param apiKey secret — {@code OPENROUTER_API_KEY}; blank = every AI feature answers 503
     * @param model the standard tier's model — {@code OPENROUTER_MODEL}
     * @param lightModel the light tier's (classification, short drafts) — {@code OPENROUTER_LIGHT_MODEL}
     * @param referer {@code HTTP-Referer} attribution header — {@code OPENROUTER_REFERER}
     * @param title {@code X-Title} attribution header
     * @param dataCollection {@code deny}: only providers that neither store nor train on prompts — {@code OPENROUTER_DATA_COLLECTION}
     * @param zdr only Zero Data Retention endpoints — {@code OPENROUTER_ZDR}
     */
    public record OpenRouter(
            @DefaultValue("https://openrouter.ai/api/v1") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("google/gemini-3.7-flash") String model,
            @DefaultValue("google/gemini-3.5-flash-lite") String lightModel,
            @DefaultValue("http://localhost:3100") String referer,
            @DefaultValue("Northline") String title,
            @DefaultValue("deny") String dataCollection,
            @DefaultValue("true") boolean zdr,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout) {

        /** The same settings with another API key (tests, the live eval). */
        public OpenRouter withKey(String key) {
            return new OpenRouter(
                    baseUrl, key, model, lightModel, referer, title, dataCollection, zdr, connectTimeout, readTimeout);
        }
    }

    /** The model a feature's requests name, or null to leave it to the adapter (the fake). */
    public @Nullable String modelFor(AiFeature feature) {
        var override = models == null ? null : models.get(feature.code().replace('_', '-'));
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        if (provider != Provider.OPENROUTER) {
            return null;
        }
        return feature.tier() == AiFeature.Tier.LIGHT ? openrouter.lightModel() : openrouter.model();
    }
}
