package ca.northline.auth.application;

import ca.northline.auth.domain.BackupCodes;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues a fresh set of ten one-time backup codes; the previous set stops working. */
@Service
@RequiredArgsConstructor
public class BackupCodeService {

    private final SecondFactors factors;
    private final Clock clock;

    @Transactional
    public List<String> regenerate(String userId) {
        var codes = BackupCodes.generate();
        factors.replaceBackupCodes(userId, codes.stream().map(BackupCodes::hash).toList(), clock.instant());
        return codes;
    }
}
