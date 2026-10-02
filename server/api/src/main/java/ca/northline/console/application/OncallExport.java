package ca.northline.console.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-113: the on-call rota (S-96, {@code identity.oncall_shifts}) for machines — the paging side (Grafana OnCall's iCal
 * schedule, a sync to PagerDuty or Opsgenie overrides, a chat bot) learns who answers a page. Read-only; the console
 * stays where shifts are planned. Authenticated by one shared token ({@code ONCALL_EXPORT_TOKEN}); off without it.
 */
public interface OncallExport {

    /** Whether a token is configured (otherwise the export does not exist: 404). */
    boolean enabled();

    /** Whether {@code presented} is the configured token (constant time). */
    boolean accepts(@Nullable String presented);

    /** Shifts from {@code back} ago to {@code ahead} from now, and who is on call now. */
    Export export();

    /**
     * @param email the person's sign-in email (the paging tool's user name); null when the account has none
     */
    record Entry(
            String shiftId,
            String userId,
            String name,
            @Nullable String email,
            Instant startsAt,
            Instant endsAt,
            String duty) {}

    record Export(Instant asOf, List<Entry> now, List<Entry> shifts) {

        public Export {
            now = List.copyOf(now);
            shifts = List.copyOf(shifts);
        }
    }
}
