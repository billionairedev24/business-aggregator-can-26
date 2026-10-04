package ca.northline.golive.application;

import java.util.List;

/** Outbound port: S-121's UAT go/no-go, as the checklist needs it (adapter over {@code uat.api.UatReadiness}). */
public interface UatVerdict {

    Verdict verdict();

    /**
     * @param blocking blocking items open or fixed but not verified
     * @param untriaged blockers participants reported that nobody triaged yet
     * @param reasons the report's reason codes for a no-go
     */
    record Verdict(boolean go, int blocking, int untriaged, List<String> reasons) {
        public Verdict {
            reasons = List.copyOf(reasons);
        }
    }
}
