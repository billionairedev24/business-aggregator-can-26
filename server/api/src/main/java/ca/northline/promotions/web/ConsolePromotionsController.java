package ca.northline.promotions.web;

import ca.northline.promotions.application.ManagePromoCodes;
import ca.northline.promotions.application.ManagePromoCodes.CodeView;
import ca.northline.promotions.domain.PromoMessages;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
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
 * Platform console — promo codes (mobile gaps part 2), on the Finance screen: finance and admins make and switch codes
 * ({@code promotions} action); every change is audit-logged. Codes aren't sent to anyone from here: a promotion
 * message is commercial and goes out only to people who consented (S-108, CASL).
 *
 * <pre>
 * GET   /api/v1/console/promotions/codes             {items: [CodeView]} — newest first, with uses and discount given
 * POST  /api/v1/console/promotions/codes             201 CodeView — {code, kind: percent|amount, percent | amountCents,
 *                                                    maxDiscountCents?, minSpendCents?, startsAt, endsAt,
 *                                                    perCustomerLimit?, totalLimit?, fundedBy: northline|merchant,
 *                                                    merchantId?, appliesTo: [goods|food|service], description?}
 * PATCH /api/v1/console/promotions/codes/{id}        {active} → CodeView
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/promotions/codes")
@RequiredArgsConstructor
class ConsolePromotionsController {

    private final ManagePromoCodes codes;

    record CodeRequest(
            @NotBlank(message = PromoMessages.CODE_FORMAT) String code,
            @Nullable String description,
            @Nullable String kind,
            @Nullable Integer percent,
            @Nullable Long amountCents,
            @Nullable Long maxDiscountCents,
            @Nullable Long minSpendCents,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable Integer perCustomerLimit,
            @Nullable Integer totalLimit,
            @Nullable String fundedBy,
            @Nullable String merchantId,
            @Nullable List<String> appliesTo) {}

    record ActiveRequest(@NotNull(message = "Choose on or off.") Boolean active) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.FINANCE)
    ListResponse<CodeView> list() {
        return new ListResponse<>(codes.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.PROMOTIONS)
    CodeView create(@Valid @RequestBody CodeRequest body, CurrentStaff staff) {
        return codes.create(
                new ManagePromoCodes.NewCode(
                        body.code(),
                        body.description(),
                        body.kind(),
                        body.percent(),
                        body.amountCents(),
                        body.maxDiscountCents(),
                        body.minSpendCents(),
                        body.startsAt(),
                        body.endsAt(),
                        body.perCustomerLimit(),
                        body.totalLimit(),
                        body.fundedBy(),
                        body.merchantId(),
                        Objects.requireNonNullElse(body.appliesTo(), List.of())),
                staff.userId(),
                staff.roleCodes());
    }

    @PatchMapping("/{id}")
    @RequiresConsole(value = ConsoleScreen.FINANCE, actions = ConsoleAction.PROMOTIONS)
    CodeView active(@PathVariable String id, @Valid @RequestBody ActiveRequest body, CurrentStaff staff) {
        return codes.setActive(id, body.active(), staff.userId(), staff.roleCodes());
    }
}
