package ca.northline.console.application;

import ca.northline.golive.api.PilotReadiness;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-118: the go-live checklist's pilot-business count from the pilot pipeline (S-120). Ready = live, or waiting only for
 * the market to launch (approved, page published, listings customers see; searchable once the market is live).
 */
@Component
@RequiredArgsConstructor
class PilotGoLiveReadiness implements PilotReadiness {

    private final PilotOnboarding pilots;

    @Override
    @Transactional(readOnly = true)
    public Counts counts(String marketId) {
        var board = pilots.board(marketId);
        var ready = (int) board.items().stream()
                .filter(r -> "live".equals(r.stage())
                        || java.util.Optional.ofNullable(r.next())
                                .map(PilotOnboarding.PilotStep::action)
                                .filter("market_launch"::equals)
                                .isPresent())
                .count();
        return new Counts(ready, board.items().size(), board.blocked());
    }
}
