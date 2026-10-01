package ca.northline.trust.application;

import java.util.List;

/**
 * S-133 use case: screen what was written since the last run — listings submitted for vetting, reviews, messages — and
 * put what the model suggests into the staff queue as open {@code ai_screen} flags with its explanation. Nothing is
 * hidden, rejected or blocked: staff decide. Run by the scheduler every {@code northline.trust.ai.interval}.
 */
public interface ScreenTrustContent {

    Result screenNew();

    /**
     * @param screened items the model looked at
     * @param flagged flags raised
     * @param deferred sources that stopped early (AI unavailable or over budget) and continue next run
     */
    record Result(int screened, int flagged, List<String> deferred) {}
}
