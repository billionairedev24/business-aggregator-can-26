package ca.northline.account.web;

import ca.northline.account.application.ViewAccountSummary;
import ca.northline.account.application.ViewActivity;
import ca.northline.account.application.ViewActivity.CaseRef;
import ca.northline.account.application.ViewActivity.Item;
import ca.northline.account.application.ViewUpcoming;
import ca.northline.account.application.ViewUpcoming.Upcoming;
import ca.northline.account.application.ViewWallet;
import ca.northline.account.application.ViewWallet.Wallet;
import ca.northline.account.domain.ActivityAction;
import ca.northline.account.domain.ActivityKind;
import ca.northline.account.domain.ActivityStatus;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in consumer's activity (S-58). Personal: never cached.
 *
 * <pre>
 * GET /api/v1/me/activity          Orders &amp; bookings: orders, bookings, open quote requests (active first)
 * GET /api/v1/me/upcoming          the home page's "Your week" (texts in the caller's language)
 * GET /api/v1/me/wallet            points, eight weeks of earning, Northline Plus
 * GET /api/v1/me/account-summary   the account menu's values
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class AccountActivityController {

    private final ViewActivity activity;
    private final ViewUpcoming upcoming;
    private final ViewWallet wallet;
    private final ViewAccountSummary summary;

    /**
     * One row of Orders &amp; bookings. {@code status} and {@code action} are codes the web words in the reader's
     * language; {@code tone} is the tag's colour ({@code accent | neutral | accent-2}).
     */
    record ActivityResponse(
            String id,
            ActivityKind kind,
            @Nullable String ref,
            String title,
            List<String> with,
            @Nullable String delivery,
            int shops,
            int items,
            Instant when,
            @Nullable Instant whenEnd,
            long amountCents,
            ActivityStatus status,
            String tone,
            boolean active,
            @Nullable CaseRef caseRef,
            ActivityAction action,
            @Nullable String href) {

        static ActivityResponse of(Item i) {
            return new ActivityResponse(
                    i.id(),
                    i.kind(),
                    i.ref(),
                    i.title(),
                    i.with(),
                    i.delivery(),
                    i.shops(),
                    i.items(),
                    i.when(),
                    i.whenEnd(),
                    i.amountCents(),
                    i.status(),
                    i.tone(),
                    i.active(),
                    i.caseRef(),
                    i.action(),
                    i.href());
        }
    }

    /** Every field may be absent for the web; S-59 adds the settings ones. */
    record SummaryResponse(
            @Nullable BigDecimal reliability,
            ViewAccountSummary.Points points,
            boolean plus,
            int activeOrders,
            int favourites,
            int openCases) {}

    @Operation(summary = "Orders & bookings: the caller's orders, bookings and open quote requests")
    @GetMapping("/activity")
    ResponseEntity<ListResponse<ActivityResponse>> activity(CurrentUser user) {
        var items =
                activity.items(user.userId()).stream().map(ActivityResponse::of).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ListResponse<>(items));
    }

    @Operation(summary = "Your week: the caller's orders, bookings and quotes of the next seven days")
    @GetMapping("/upcoming")
    ResponseEntity<ListResponse<Upcoming>> upcoming(CurrentUser user, Locale locale) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ListResponse<>(upcoming.upcoming(user.userId(), locale)));
    }

    @Operation(summary = "Wallet & points: balance, eight weeks of earning, Northline Plus")
    @GetMapping("/wallet")
    ResponseEntity<Wallet> wallet(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wallet.wallet(user.userId()));
    }

    @Operation(summary = "The account menu's values")
    @GetMapping("/account-summary")
    ResponseEntity<SummaryResponse> summary(CurrentUser user) {
        var s = summary.summary(user.userId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SummaryResponse(
                        s.reliability(), s.points(), s.plus(), s.activeOrders(), s.favourites(), s.openCases()));
    }
}
