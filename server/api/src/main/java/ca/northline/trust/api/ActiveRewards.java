package ca.northline.trust.api;

import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** S-75: the reward a business funds right now, for its public page ("2× points on brake jobs until Oct 1"). */
public interface ActiveRewards {

    Optional<Reward> running(String merchantId);

    record Reward(int multiplier, @Nullable String label, LocalDate endsOn) {}
}
