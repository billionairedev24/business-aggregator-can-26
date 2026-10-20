package ca.northline.trust.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-75: the reward a business funds right now, for its public page ("2× points on brake jobs until Oct 1"). S-152: a
 * reward runs only while its budget lasts.
 */
public interface ActiveRewards {

    Optional<Reward> running(String merchantId);

    /** Every reward running today (each business's own date) — "rewards near you" filters them by place. */
    List<Running> all();

    record Reward(int multiplier, @Nullable String label, LocalDate endsOn) {}

    record Running(
            String merchantId, int multiplier, @Nullable String label, LocalDate endsOn) {}
}
