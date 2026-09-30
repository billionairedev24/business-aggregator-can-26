package ca.northline.payments.web;

import static ca.northline.shared.security.MerchantPermission.FINANCE_READ;
import static ca.northline.shared.security.MerchantPermission.MANAGE;

import ca.northline.payments.application.ChangeBankAccount;
import ca.northline.payments.application.MovePayouts;
import ca.northline.payments.application.StepUpVerifier;
import ca.northline.payments.application.ViewPayouts;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutSchedule;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payouts (read: owner + bookkeeper; changes: owner only). Money-moving POSTs need {@code Idempotency-Key}; instant
 * payouts and bank account confirmation also a fresh step-up proof in {@code X-Step-Up}.
 *
 * <pre>
 * GET  …/payouts/overview                                  available, next payout, schedule, bank account
 * GET  …/payouts?limit=                                    history
 * POST …/payouts/instant                  {amountCents}    Idempotency-Key + X-Step-Up
 * GET  …/payouts/schedule/preview?frequency=&weekday=&monthlyAnchor=&reserve=
 * PUT  …/payouts/schedule                 {frequency, weekday?, monthlyAnchor?, reserve}
 * POST …/payouts/bank-accounts/link-session                Stripe Financial Connections (or the fake)
 * POST …/payouts/bank-accounts            {method, …}      → draft
 * POST …/payouts/bank-accounts/{id}/confirm                Idempotency-Key + X-Step-Up → 24 h hold
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/payouts")
@RequiredArgsConstructor
class PayoutController {

    static final String STEP_UP = "X-Step-Up";

    private final ViewPayouts view;
    private final MovePayouts move;
    private final ChangeBankAccount bank;
    private final StepUpVerifier stepUp;
    private final PaymentsIdempotency idempotency;
    private final PaymentsWebMapper mapper;

    @GetMapping("/overview")
    @RequiresMerchant(FINANCE_READ)
    PayoutResponses.Overview overview(@PathVariable String merchantId) {
        return mapper.toResponse(view.overview(merchantId));
    }

    @GetMapping
    @RequiresMerchant(FINANCE_READ)
    ListResponse<PayoutResponses.PayoutLine> history(
            @PathVariable String merchantId, @RequestParam(defaultValue = "100") int limit) {
        return new ListResponse<>(mapper.toPayouts(view.history(merchantId, limit)));
    }

    @PostMapping("/instant")
    @RequiresMerchant(MANAGE)
    ResponseEntity<String> instant(
            @PathVariable String merchantId,
            @Valid @RequestBody PayoutRequests.InstantPayout body,
            @RequestHeader(name = PaymentsIdempotency.HEADER, required = false) @Nullable String key,
            @RequestHeader(name = STEP_UP, required = false) @Nullable String proof,
            CurrentMember member) {
        return idempotency.run(scope(member, "instant-payout"), key, body, HttpStatus.CREATED, () -> {
            stepUp.verify(member.userId(), proof);
            var command = new MovePayouts.InstantCommand(
                    merchantId,
                    Objects.requireNonNull(body.amountCents()),
                    member.userId(),
                    Objects.requireNonNull(key));
            return mapper.toResponse(move.instant(command));
        });
    }

    @GetMapping("/schedule/preview")
    @RequiresMerchant(FINANCE_READ)
    PayoutResponses.Preview preview(
            @PathVariable String merchantId,
            @RequestParam String frequency,
            @RequestParam @Nullable Integer weekday,
            @RequestParam @Nullable String monthlyAnchor,
            @RequestParam(defaultValue = "none") String reserve) {
        var schedule = new PayoutSchedule(
                code(PayoutSchedule.Frequency.class, "frequency", frequency),
                weekday,
                monthlyAnchor == null || monthlyAnchor.isBlank()
                        ? null
                        : code(PayoutSchedule.MonthlyAnchor.class, "monthlyAnchor", monthlyAnchor),
                code(PayoutSchedule.Reserve.class, "reserve", reserve));
        return mapper.toResponse(view.preview(merchantId, schedule));
    }

    @PutMapping("/schedule")
    @RequiresMerchant(MANAGE)
    PayoutResponses.Overview schedule(
            @PathVariable String merchantId, @Valid @RequestBody PayoutRequests.Schedule body, CurrentMember member) {
        return mapper.toResponse(move.changeSchedule(merchantId, body.toSchedule(), member.userId()));
    }

    @PostMapping("/bank-accounts/link-session")
    @RequiresMerchant(MANAGE)
    PayoutResponses.LinkSession linkSession(@PathVariable String merchantId) {
        return mapper.toResponse(bank.linkSession(merchantId));
    }

    @PostMapping("/bank-accounts")
    @RequiresMerchant(MANAGE)
    ResponseEntity<PayoutResponses.Account> prepare(
            @PathVariable String merchantId,
            @Valid @RequestBody PayoutRequests.BankAccount body,
            CurrentMember member) {
        body.requireManualFields();
        var draft = bank.prepare(new ChangeBankAccount.Prepare(
                merchantId,
                Objects.requireNonNull(body.method()),
                body.linkedAccount(),
                body.institution(),
                body.transit(),
                body.accountNumber(),
                body.holderName(),
                member.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(draft));
    }

    @PostMapping("/bank-accounts/{accountId}/confirm")
    @RequiresMerchant(MANAGE)
    ResponseEntity<String> confirm(
            @PathVariable String merchantId,
            @PathVariable String accountId,
            @RequestHeader(name = PaymentsIdempotency.HEADER, required = false) @Nullable String key,
            @RequestHeader(name = STEP_UP, required = false) @Nullable String proof,
            CurrentMember member) {
        return idempotency.run(scope(member, "bank-account"), key, accountId, HttpStatus.OK, () -> {
            stepUp.verify(member.userId(), proof);
            PayoutAccount account = bank.confirm(merchantId, accountId, member.userId());
            return mapper.toResponse(account);
        });
    }

    static String scope(CurrentMember member, String operation) {
        return member.merchantId() + ":" + member.userId() + ":" + operation;
    }

    private static <E extends Enum<E> & CodedEnum> E code(Class<E> type, String field, String value) {
        try {
            return CodedEnum.fromCode(type, value);
        } catch (IllegalArgumentException e) {
            throw RuleViolation.of(field, "format", "Unknown " + field + ".");
        }
    }
}
