package ca.northline.ai.application;

import ca.northline.ai.api.AiCompletions.Caller;

/**
 * Outbound port: per-person request rate and daily token budgets per person and per business (Valkey in the cloud
 * profiles, memory under local/test). Limits: {@link AiProperties.Budget}.
 */
public interface AiBudgets {

    /**
     * Counts one model request against the person's per-minute rate and checks both token budgets.
     *
     * @throws ca.northline.ai.api.AiRateLimited over a limit
     * @throws ca.northline.ai.api.AiUnavailable the store can't answer (fail closed: AI costs money)
     */
    void admit(Caller caller);

    /** Adds the tokens a call used to the person's and the business's day. Never throws. */
    void charge(Caller caller, long tokens);
}
