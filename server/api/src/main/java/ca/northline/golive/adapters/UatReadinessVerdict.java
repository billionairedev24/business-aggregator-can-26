package ca.northline.golive.adapters;

import ca.northline.golive.application.UatVerdict;
import org.springframework.stereotype.Component;

/** TEMPORARY until S-121 (uat module) is on main: no UAT report → the gate fails with no participants. */
@Component
class UatReadinessVerdict implements UatVerdict {

    @Override
    public Verdict verdict() {
        return new Verdict(false, 0, 0, java.util.List.of("no_participants"));
    }
}
