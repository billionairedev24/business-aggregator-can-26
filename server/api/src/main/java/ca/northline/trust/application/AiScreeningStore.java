package ca.northline.trust.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-133): the AI screening's records and read marks, the reviews it reads, the weekly anomaly scan's
 * per-business counts and its runs ({@code trust.ai_screenings}, {@code ai_screening_marks}, {@code anomaly_scans}).
 */
public interface AiScreeningStore {

    /** Takes the source's run lock for the current transaction; false when another replica holds it. */
    boolean lock(String source);

    Optional<Mark> mark(String source);

    void saveMark(String source, Mark mark);

    void record(Screening screening);

    /** Reviews of businesses with text, written after the mark, oldest first. */
    List<ReviewText> reviewsAfter(Mark after, int limit);

    /** Per business with any review or flag in [{@code from}, {@code to}): its counts, and its previous 8 weeks'. */
    List<BusinessWeek> week(Instant from, Instant to);

    /** Claims the market's scan for the week; false when it already ran (another replica, a re-run). */
    boolean claimScan(String id, String market, LocalDate weekStart);

    void completeScan(
            String id,
            int merchants,
            int flagsRaised,
            @Nullable String summary,
            @Nullable String model,
            @Nullable String prompt);

    /** Where the job has read a source up to: the last item's time and id. */
    record Mark(Instant at, String id) {}

    record Screening(
            String id,
            String targetType,
            String targetId,
            @Nullable String merchantId,
            boolean flagged,
            List<String> categories,
            @Nullable String explanation,
            String model,
            String prompt) {}

    record ReviewText(String reviewId, String merchantId, int rating, String text, Instant createdAt) {}

    /**
     * @param priorReviews reviews in the 8 weeks before {@code from}
     * @param offPlatformFlags {@code off_platform_payment} flags raised in the week
     * @param aiFlags {@code ai_screen} flags raised in the week
     */
    record BusinessWeek(
            String merchantId,
            int reviews,
            @Nullable Double averageRating,
            int priorReviews,
            @Nullable Double priorAverageRating,
            int offPlatformFlags,
            int aiFlags) {}
}
