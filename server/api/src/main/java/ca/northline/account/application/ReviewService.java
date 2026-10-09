package ca.northline.account.application;

import ca.northline.account.application.Reviews.Context;
import ca.northline.account.application.Reviews.ReviewWhatYouBought;
import ca.northline.account.application.Reviews.Target;
import ca.northline.account.application.Reviews.Write;
import ca.northline.booking.api.CustomerHistory;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.orders.api.CustomerOrders;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.ReviewPosting;
import ca.northline.trust.api.ReviewPosting.Posted;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewWhatYouBought}: what was bought comes from booking and orders (a paid booking completed or signed off; a
 * goods or food order delivered or confirmed), the review itself from trust ({@link ReviewPosting}).
 */
@Service
@RequiredArgsConstructor
@Transactional
class ReviewService implements ReviewWhatYouBought {

    static final Set<String> DONE_BOOKINGS = Set.of("completed", "signed_off");
    static final Set<String> DONE_ORDERS = Set.of("delivered", "confirmed");

    private final CustomerHistory history;
    private final CustomerOrders orders;
    private final ReviewPosting reviews;
    private final PersonDirectory people;
    private final Businesses businesses;
    private final Clock clock;

    /** What was bought, before reviews: who, what, when it was done (null = not yet), whether it can be reviewed. */
    private record Bought(
            String kind,
            String id,
            @Nullable String ref,
            String refType,
            @Nullable Instant doneAt,
            boolean paid,
            LinkedHashMap<String, String> labels) {}

    @Override
    @Transactional(readOnly = true)
    public Context context(String customerId, String kind, String id) {
        var b = bought(customerId, kind, id);
        var mine = reviews.mine(customerId, List.of(id));
        var names = businesses.of(b.labels().keySet());
        var now = clock.instant();
        var targets = new ArrayList<Target>();
        b.labels().forEach((merchantId, label) -> {
            var review = mine.stream()
                    .filter(r -> r.merchantId().equals(merchantId))
                    .findFirst()
                    .orElse(null);
            var business = names.get(merchantId);
            targets.add(new Target(
                    merchantId,
                    business == null ? "" : business.name(),
                    business == null ? null : business.slug(),
                    label,
                    status(b, review, now),
                    review));
        });
        var doneAt = b.doneAt();
        return new Context(kind, id, b.ref(), doneAt == null ? null : doneAt.plus(ReviewPosting.WINDOW), targets);
    }

    @Override
    public Posted post(String customerId, Write w, Locale locale) {
        var b = bought(customerId, w.kind(), w.id());
        var merchantId = w.merchantId();
        if (merchantId == null || merchantId.isBlank()) {
            if (b.labels().size() != 1) {
                throw RuleViolation.of("merchantId", "required", ReviewPosting.NOT_A_SELLER);
            }
            merchantId = b.labels().keySet().iterator().next();
        }
        if (!b.labels().containsKey(merchantId)) {
            throw RuleViolation.of("merchantId", "required", ReviewPosting.NOT_A_SELLER);
        }
        switch (status(b, null, clock.instant())) {
            case "not_yet" -> throw new Conflict("not_reviewable", ReviewPosting.NOT_REVIEWABLE);
            case "closed" -> throw new Conflict("review_window", ReviewPosting.WINDOW_CLOSED);
            default -> {}
        }
        var author = people.people(List.of(customerId)).get(customerId);
        return reviews.post(new ReviewPosting.Draft(
                customerId,
                author == null ? "Customer" : author.shortName(),
                b.refType(),
                b.id(),
                merchantId,
                b.labels().get(merchantId),
                w.rating(),
                w.tags(),
                w.text(),
                locale.getLanguage().equals("fr") ? "fr" : "en"));
    }

    @Override
    public Posted edit(String customerId, String reviewId, int rating, List<String> tags, @Nullable String text) {
        return reviews.edit(customerId, reviewId, rating, tags, text);
    }

    private static String status(Bought b, @Nullable Posted review, Instant now) {
        if (review != null) {
            return "reviewed";
        }
        var doneAt = b.doneAt();
        if (doneAt == null) {
            return "not_yet";
        }
        if (!b.paid() || !now.isBefore(doneAt.plus(ReviewPosting.WINDOW))) {
            return "closed";
        }
        return "open";
    }

    private Bought bought(String customerId, String kind, String id) {
        return switch (kind) {
            case "booking" -> {
                var b = history.booking(customerId, id).orElseThrow(() -> new NotFound("booking", id));
                var done = DONE_BOOKINGS.contains(b.state())
                        ? Objects.requireNonNullElse(b.completedAt(), b.endsAt())
                        : null;
                var labels = new LinkedHashMap<String, String>();
                labels.put(b.merchantId(), b.title());
                yield new Bought(kind, id, b.ref(), "booking", done, b.paid(), labels);
            }
            case "order", "food" -> {
                var d = orders.detail(customerId, id).orElseThrow(() -> new NotFound("order", id));
                var food = "food".equals(d.order().type());
                if (food != "food".equals(kind)) {
                    throw new NotFound("order", id);
                }
                var done = DONE_ORDERS.contains(d.order().state())
                        ? Objects.requireNonNullElse(
                                d.order().deliveredAt(), d.order().placedAt())
                        : null;
                var labels = new LinkedHashMap<String, String>();
                for (var line : d.lines()) {
                    labels.merge(line.merchantId(), line.title(), (a, _) -> a.endsWith("…") ? a : a + " …");
                }
                if (food && d.order().ref() != null) {
                    labels.replaceAll((_, title) -> "Food order " + d.order().ref());
                }
                yield new Bought(kind, id, d.order().ref(), "order", done, true, labels);
            }
            default -> throw new NotFound("review", kind);
        };
    }
}
