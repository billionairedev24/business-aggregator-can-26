package ca.northline.trust.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import ca.northline.trust.api.ReviewPosting;
import ca.northline.trust.application.ReviewStore.Authored;
import ca.northline.trust.domain.ReviewRules;
import ca.northline.trust.domain.ReviewScreen;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers' reviews (mobile gaps part 2): checks the stars, tags and words, screens the words
 * ({@link ReviewScreen}: masked personal information and profanity raise a {@code review_screened} flag), stores the
 * review with its 24-hour edit window, and lets trust &amp; safety hide it.
 */
@Service
@RequiredArgsConstructor
@Transactional
class ReviewPostingService implements ReviewPosting, ReviewModeration {

    private final ReviewStore reviews;
    private final TrustFlagStore flags;
    private final AuditTrail audit;
    private final Clock clock;

    private record Content(int rating, List<String> tags, @Nullable String text, boolean screened, List<String> why) {}

    @Override
    public Posted post(Draft d) {
        var content = content(d.rating(), d.tags(), d.text());
        var now = clock.instant();
        var review = new Authored(
                Ids.next(),
                d.authorId(),
                d.merchantId(),
                d.refType(),
                d.refId(),
                content.rating(),
                content.tags(),
                content.text(),
                "fr".equals(d.lang()) ? "fr" : "en",
                content.screened(),
                now,
                now.plus(ReviewRules.EDIT_WINDOW),
                null,
                null,
                null);
        if (!reviews.insert(review, d.authorName(), d.jobLabel())) {
            throw new Conflict("already_reviewed", ReviewRules.ALREADY_REVIEWED);
        }
        screened(review.id(), d.merchantId(), d.authorId(), content);
        return posted(review);
    }

    @Override
    public Posted edit(String authorId, String reviewId, int rating, List<String> tags, @Nullable String text) {
        var review = reviews.authored(reviewId)
                .filter(r -> r.authorId().equals(authorId))
                .orElseThrow(() -> new NotFound("review", reviewId));
        var content = content(rating, tags, text);
        var now = clock.instant();
        if (review.reply() != null
                || review.editUntil() == null
                || !now.isBefore(review.editUntil())
                || !reviews.edit(reviewId, content.rating(), content.tags(), content.text(), content.screened(), now)) {
            throw new Conflict("review_locked", ReviewRules.EDIT_CLOSED);
        }
        screened(reviewId, review.merchantId(), authorId, content);
        return posted(reviews.authored(reviewId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Posted> mine(String authorId, Collection<String> refIds) {
        return reviews.byAuthor(authorId, refIds).stream().map(this::posted).toList();
    }

    @Override
    public boolean hide(String reviewId, String reason, String staffId, String role, @Nullable String note) {
        if (reason.isBlank()) {
            throw RuleViolation.of("reason", "required", REASON_REQUIRED);
        }
        var review = reviews.authored(reviewId).orElseThrow(() -> new NotFound("review", reviewId));
        var changed = reviews.moderate(reviewId, reason.strip(), staffId, clock.instant());
        if (changed) {
            audited("trust.review_hidden", review, staffId, role, reason.strip(), note);
        }
        return changed;
    }

    @Override
    public boolean show(String reviewId, String staffId, String role, @Nullable String note) {
        var review = reviews.authored(reviewId).orElseThrow(() -> new NotFound("review", reviewId));
        var changed = reviews.moderate(reviewId, null, staffId, clock.instant());
        if (changed) {
            audited("trust.review_shown", review, staffId, role, null, note);
        }
        return changed;
    }

    private void audited(
            String action, Authored review, String staffId, String role, @Nullable String reason, @Nullable String note) {
        var after = new java.util.LinkedHashMap<String, String>();
        after.put("hidden", String.valueOf(reason != null));
        if (reason != null) {
            after.put("reason", reason);
        }
        if (note != null && !note.isBlank()) {
            after.put("note", note.strip());
        }
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                role,
                action,
                "review",
                review.id(),
                Map.of("merchantId", review.merchantId(), "hidden", String.valueOf(review.hiddenAt() != null)),
                after));
    }

    /** A review whose words were masked is published masked, and staff get a flag to look at it. */
    private void screened(String reviewId, String merchantId, String authorId, Content content) {
        if (content.screened()) {
            flags.raiseUnlessOpen(
                    Ids.next(),
                    "review",
                    reviewId,
                    "review_screened",
                    merchantId,
                    authorId,
                    Map.of("categories", String.join(",", content.why())));
        }
    }

    private static Content content(int rating, List<String> rawTags, @Nullable String rawText) {
        var violations = new ArrayList<Violation>();
        if (rating < 1 || rating > 5) {
            violations.add(new Violation("rating", "range", ReviewRules.RATING_REQUIRED));
        }
        var tags = List.copyOf(new LinkedHashSet<>(rawTags));
        if (tags.size() > ReviewRules.TAGS_MAX || !ReviewRules.TAGS.containsAll(tags)) {
            violations.add(new Violation("tags", "option", ReviewRules.TAGS_INVALID));
        }
        var text = rawText == null || rawText.isBlank() ? null : rawText.strip();
        if (text != null && text.length() < ReviewRules.TEXT_MIN) {
            violations.add(new Violation("text", "length", ReviewRules.TEXT_TOO_SHORT));
        } else if (text != null && text.length() > ReviewRules.TEXT_MAX) {
            violations.add(new Violation("text", "length", ReviewRules.TEXT_TOO_LONG));
        }
        if (!violations.isEmpty()) {
            throw new RuleViolation(violations);
        }
        if (text == null) {
            return new Content(rating, tags, null, false, List.of());
        }
        var screened = ReviewScreen.screen(text);
        return new Content(rating, tags, screened.text(), screened.masked(), screened.reasons());
    }

    private Posted posted(Authored r) {
        return new Posted(
                r.id(),
                r.merchantId(),
                r.refType(),
                r.refId(),
                r.rating(),
                r.tags(),
                r.text(),
                r.screened(),
                r.createdAt(),
                r.reply() == null ? r.editUntil() : null,
                r.editedAt(),
                r.reply(),
                r.hiddenAt() != null);
    }
}
