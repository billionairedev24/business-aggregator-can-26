package ca.northline.uat.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * S-121: the UAT go/no-go — open blocking items, sign-off coverage per persona and the trend. The console shows it
 * ({@code GET /api/v1/console/uat/go-no-go}, CSV at {@code …/export}); S-118's go-live checklist reads it here.
 */
public interface UatReadiness {

    GoNoGoReport report(Locale locale);

    /**
     * @param verdict {@code go | no_go}
     * @param reasons why not (empty for a go)
     * @param blockingOpen accepted as blocking and not fixed
     * @param blockingUnverified fixed, not verified yet
     * @param untriagedBlockers participants said "blocker" and nobody has triaged it yet
     * @param trend the last 14 days in the platform's time zone, oldest first
     */
    record GoNoGoReport(
            Instant generatedAt,
            String verdict,
            List<GoNoGoReason> reasons,
            int blockingOpen,
            int blockingUnverified,
            int untriagedBlockers,
            List<BlockingItem> blockingItems,
            List<PersonaCoverage> coverage,
            List<TrendDay> trend) {

        public GoNoGoReport {
            reasons = List.copyOf(reasons);
            blockingItems = List.copyOf(blockingItems);
            coverage = List.copyOf(coverage);
            trend = List.copyOf(trend);
        }

        public boolean go() {
            return "go".equals(verdict);
        }
    }

    /**
     * @param code {@code blocking_open | blocking_unverified | blockers_untriaged | no_participants | signoffs_pending
     *     | signoffs_blocked}
     * @param persona the persona it concerns, null for the whole pilot
     * @param text the reason in the caller's language
     */
    record GoNoGoReason(String code, int count, @Nullable String persona, String text) {}

    /**
     * An open blocking item (accepted as blocking, fixed but not verified, or a reported blocker not triaged yet).
     *
     * @param reference {@code UAT-1001}
     * @param summary the first line of the feedback, at most 120 characters
     */
    record BlockingItem(
            String id,
            String reference,
            String state,
            String persona,
            String app,
            String summary,
            @Nullable String ownerName,
            @Nullable String trackerUrl,
            Instant reportedAt,
            int reports) {}

    /** @param scriptVersion the version participants are asked to sign off */
    record PersonaCoverage(
            String persona,
            String script,
            String scriptTitle,
            String scriptVersion,
            int participants,
            int signedOff,
            int withComments,
            int blocked,
            int pending,
            boolean complete) {}

    /**
     * @param reported feedback sent that day
     * @param openBlocking blocking items open (accepted or fixed) at the end of the day
     * @param resolved items verified, closed, won't fix or marked duplicate that day
     */
    record TrendDay(LocalDate date, int reported, int openBlocking, int resolved) {}
}
