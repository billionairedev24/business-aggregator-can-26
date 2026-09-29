package ca.northline.trust.domain;

import static ca.northline.trust.domain.ReviewRules.NOTE_MAX;
import static ca.northline.trust.domain.ReviewRules.NOTE_REQUIRED;
import static ca.northline.trust.domain.ReviewRules.NOTE_TOO_LONG;
import static ca.northline.trust.domain.ReviewRules.REPLY_MAX;
import static ca.northline.trust.domain.ReviewRules.REPLY_REQUIRED;
import static ca.northline.trust.domain.ReviewRules.REPLY_TOO_LONG;

import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A verified review of a business, written by the customer of a completed booking or delivered order. Its rating and
 * text never change (DB trigger {@code trust.reviews_immutable}); the business may reply publicly once and report it
 * once.
 */
public record Review(
        String id,
        String merchantId,
        int rating,
        @Nullable String authorName,
        @Nullable String jobLabel,
        String refType,
        @Nullable String text,
        List<String> tags,
        Instant createdAt,
        @Nullable String reply,
        @Nullable Instant replyAt,
        @Nullable Instant reportedAt,
        @Nullable ReportReason reportReason) {

    public Review {
        tags = List.copyOf(tags);
    }

    public Review replied(String rawText, Instant now) {
        if (reply != null) {
            throw new Conflict("review_already_replied", "You've already replied to this review.");
        }
        var text = rawText.strip();
        if (text.isEmpty()) {
            throw RuleViolation.of("text", "required", REPLY_REQUIRED);
        }
        if (text.length() > REPLY_MAX) {
            throw RuleViolation.of("text", "length", REPLY_TOO_LONG);
        }
        return new Review(
                id,
                merchantId,
                rating,
                authorName,
                jobLabel,
                refType,
                this.text,
                tags,
                createdAt,
                text,
                now,
                reportedAt,
                reportReason);
    }

    /** @return the review as reported, with the trimmed note (null when none) */
    public Reported reported(ReportReason reason, @Nullable String rawNote, Instant now) {
        if (reportedAt != null) {
            throw new Conflict("review_already_reported", "You've already reported this review.");
        }
        var note = rawNote == null || rawNote.isBlank() ? null : rawNote.strip();
        if (reason == ReportReason.OTHER && note == null) {
            throw RuleViolation.of("note", "required", NOTE_REQUIRED);
        }
        if (note != null && note.length() > NOTE_MAX) {
            throw RuleViolation.of("note", "length", NOTE_TOO_LONG);
        }
        var review = new Review(
                id,
                merchantId,
                rating,
                authorName,
                jobLabel,
                refType,
                text,
                tags,
                createdAt,
                reply,
                replyAt,
                now,
                reason);
        return new Reported(review, note);
    }

    public record Reported(Review review, @Nullable String note) {}
}
