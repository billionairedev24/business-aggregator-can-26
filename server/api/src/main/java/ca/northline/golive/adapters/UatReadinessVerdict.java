package ca.northline.golive.adapters;

import ca.northline.golive.application.UatVerdict;
import ca.northline.uat.api.UatReadiness;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link UatVerdict} from S-121's go/no-go report ({@link UatReadiness}): the pilot group's UAT, platform-wide. */
@Component
@RequiredArgsConstructor
class UatReadinessVerdict implements UatVerdict {

    private final UatReadiness uat;

    @Override
    public Verdict verdict() {
        var report = uat.report(Locale.CANADA);
        return new Verdict(
                report.go(),
                report.blockingOpen() + report.blockingUnverified(),
                report.untriagedBlockers(),
                report.reasons().stream().map(UatReadiness.GoNoGoReason::code).toList());
    }
}
