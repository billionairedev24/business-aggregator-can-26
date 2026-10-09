package ca.northline.account.web;

import ca.northline.account.application.Reviews.Context;
import ca.northline.account.application.Reviews.ReviewWhatYouBought;
import ca.northline.account.application.Reviews.Write;
import ca.northline.shared.security.CurrentUser;
import ca.northline.trust.api.ReviewPosting.Posted;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers' reviews (mobile gaps part 2, design 01 C11 two-way review and B9 delivered, design 06): once per business
 * of a paid, completed booking or a delivered order, within 30 days; changeable for 24 hours (until the business
 * replies). Words are screened for personal information and profanity; trust &amp; safety can hide a review.
 *
 * <pre>
 * GET   /api/v1/me/reviews/{kind}/{id}     kind booking | order | food → {kind, id, ref, reviewBy, targets: [{merchantId,
 *                                          merchantName, slug, jobLabel, status: open|reviewed|not_yet|closed, review?}]}
 * POST  /api/v1/me/reviews                 {kind, id, merchantId?, rating, tags?, text?} → 201 review
 * PATCH /api/v1/me/reviews/{reviewId}      {rating, tags?, text?} → review (409 review_locked after the window)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me/reviews")
@RequiredArgsConstructor
class MyReviewsController {

    static final String KIND = "Choose what you're reviewing.";
    static final String RATING = "Choose from 1 to 5 stars.";

    private final ReviewWhatYouBought reviews;

    record ReviewRequest(
            @NotBlank(message = KIND) @Pattern(regexp = "booking|order|food", message = KIND)
            String kind,

            @NotBlank(message = KIND) String id,
            @Nullable String merchantId,
            @NotNull(message = RATING) Integer rating,
            @Nullable List<String> tags,
            @Nullable String text) {}

    record EditRequest(
            @NotNull(message = RATING) Integer rating,
            @Nullable List<String> tags,
            @Nullable String text) {}

    @GetMapping("/{kind}/{id}")
    Context context(@PathVariable String kind, @PathVariable String id, CurrentUser user) {
        return reviews.context(user.userId(), kind, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Posted post(@Valid @RequestBody ReviewRequest body, CurrentUser user, Locale locale) {
        return reviews.post(
                user.userId(),
                new Write(
                        body.kind(),
                        body.id(),
                        body.merchantId(),
                        body.rating(),
                        Objects.requireNonNullElse(body.tags(), List.of()),
                        body.text()),
                locale);
    }

    @PatchMapping("/{reviewId}")
    Posted edit(@PathVariable String reviewId, @Valid @RequestBody EditRequest body, CurrentUser user) {
        return reviews.edit(
                user.userId(),
                reviewId,
                body.rating(),
                Objects.requireNonNullElse(body.tags(), List.of()),
                body.text());
    }
}
