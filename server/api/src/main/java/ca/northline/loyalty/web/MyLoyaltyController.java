package ca.northline.loyalty.web;

import ca.northline.loyalty.application.LoyaltyUseCases.ManagePlus;
import ca.northline.loyalty.application.LoyaltyUseCases.ManagePlus.PlusView;
import ca.northline.loyalty.application.LoyaltyUseCases.Referrals;
import ca.northline.loyalty.application.LoyaltyUseCases.Referrals.ReferralView;
import ca.northline.loyalty.application.LoyaltyUseCases.ViewPoints;
import ca.northline.loyalty.application.LoyaltyUseCases.ViewPoints.MyPoints;
import ca.northline.payments.api.IdempotentRequests;
import ca.northline.shared.Ids;
import ca.northline.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consumer's loyalty (S-152). Single-factor sessions are accepted, like every {@code /api/v1/me} endpoint; Plus is
 * billed to a card saved with a second factor (S-59), so joining needs no step-up of its own.
 *
 * <pre>
 * GET    /api/v1/me/points?limit=            balance, value, next expiry, the rules, activity (newest first)
 * GET    /api/v1/me/referral                 the person's invite code and link, what it pays, how it went
 * POST   /api/v1/me/referral/claim           {code} — a new neighbour uses a code → the view; 422 with the reason
 * GET    /api/v1/me/plus                     the household's membership, prices, benefits
 * POST   /api/v1/me/plus                     {plan} (Idempotency-Key) → 201; clientSecret when 3-D Secure is needed
 * PATCH  /api/v1/me/plus                     {plan} (Idempotency-Key) — switch monthly ↔ annual (payer only)
 * DELETE /api/v1/me/plus                     cancel at the end of the period (payer only)
 * POST   /api/v1/me/plus/resume              keep renewing (payer only)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class MyLoyaltyController {

    private final ViewPoints points;
    private final Referrals referrals;
    private final ManagePlus plus;
    private final IdempotentRequests idempotent;

    record ClaimRequest(@Nullable String code) {}

    record PlanRequest(@Nullable String plan) {}

    @Operation(summary = "The caller's points: balance, expiry, rules and activity")
    @GetMapping("/points")
    ResponseEntity<MyPoints> points(@RequestParam(defaultValue = "50") int limit, CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(points.points(user.userId(), limit));
    }

    @Operation(summary = "The caller's Invite a neighbour code and how it went")
    @GetMapping("/referral")
    ResponseEntity<ReferralView> referral(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(referrals.view(user.userId()));
    }

    @Operation(summary = "Use a neighbour's invite code")
    @PostMapping("/referral/claim")
    ReferralView claim(@RequestBody ClaimRequest body, CurrentUser user) {
        return referrals.claim(user.userId(), body.code());
    }

    @Operation(summary = "The caller's household's Northline Plus")
    @GetMapping("/plus")
    ResponseEntity<PlusView> plus(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plus.view(user.userId()));
    }

    @Operation(summary = "Join Northline Plus (monthly or annual)")
    @PostMapping("/plus")
    ResponseEntity<String> join(
            @RequestBody PlanRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            CurrentUser user) {
        return answer(idempotent.run(
                "consumer:%s:plus".formatted(user.userId()),
                key,
                body,
                HttpStatus.CREATED.value(),
                () -> plus.join(user.userId(), body.plan(), String.valueOf(key))));
    }

    @Operation(summary = "Switch the Plus plan")
    @PatchMapping("/plus")
    ResponseEntity<String> change(
            @RequestBody PlanRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            CurrentUser user) {
        return answer(idempotent.run(
                "consumer:%s:plus-plan".formatted(user.userId()),
                key,
                body,
                HttpStatus.OK.value(),
                () -> plus.changePlan(user.userId(), body.plan(), String.valueOf(key))));
    }

    @Operation(summary = "Cancel Plus at the end of the period")
    @DeleteMapping("/plus")
    PlusView cancel(CurrentUser user) {
        return plus.cancel(user.userId(), Ids.next());
    }

    @Operation(summary = "Keep Plus renewing")
    @PostMapping("/plus/resume")
    PlusView resume(CurrentUser user) {
        return plus.resume(user.userId(), Ids.next());
    }

    private static ResponseEntity<String> answer(IdempotentRequests.Outcome outcome) {
        var response = ResponseEntity.status(outcome.status()).contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
