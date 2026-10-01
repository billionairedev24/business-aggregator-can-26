package ca.northline.fulfilment.web;

import ca.northline.fulfilment.application.DispatchUseCases.CourierSummary;
import ca.northline.fulfilment.application.DispatchUseCases.DeliveryView;
import ca.northline.fulfilment.application.DispatchUseCases.DispatchConsole;
import ca.northline.fulfilment.application.DispatchUseCases.Planned;
import ca.northline.fulfilment.application.DispatchUseCases.RunDetail;
import ca.northline.fulfilment.application.DispatchUseCases.RunSummary;
import ca.northline.fulfilment.application.DispatchUseCases.ShiftView;
import ca.northline.fulfilment.domain.DeliveryRules;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's dispatch view (S-86 for the orders monitor, S-81). {@code /api/v1/console/**}: role staff + second
 * factor (SecurityConfig), and S-90's console roles: the delivery screen (dispatch, admin) for runs and couriers, its
 * {@code dispatch} action for changes, the orders screen (dispatch, support, admin) for an order's delivery. Changes are
 * written to the platform audit log. Contract: docs/CONSOLE_PLAN.md § Delivery and docs/runbooks/fulfilment.md.
 *
 * <pre>
 * GET  /api/v1/console/fulfilment/runs?market=&amp;from=&amp;to=     {items: [RunSummary]} (default: today ± 1 day)
 * GET  /api/v1/console/fulfilment/runs/{runId}                    RunDetail (the stops as the courier sees them)
 * POST /api/v1/console/fulfilment/runs/{runId}/assign {courierId}   RunSummary; 409 courier_busy / run_started
 * GET  /api/v1/console/fulfilment/orders/{orderId}                 DeliveryView
 * GET  /api/v1/console/fulfilment/couriers?market=                 {items: [CourierSummary]}
 * POST /api/v1/console/fulfilment/couriers {userId, market, vehicle}            201 CourierSummary
 * POST /api/v1/console/fulfilment/couriers/{courierId}/shifts {startsAt, endsAt} 201 ShiftView
 * POST /api/v1/console/fulfilment/plan {market?}                   {runs, assigned}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/fulfilment")
@RequiredArgsConstructor
@RequiresConsole(ConsoleScreen.DELIVERY)
class DispatchConsoleController {

    private final DispatchConsole console;
    private final Clock clock;

    record AssignRequest(
            @NotBlank(message = DeliveryRules.COURIER_REQUIRED)
            String courierId) {}

    record CourierRequest(
            @NotBlank(message = DeliveryRules.PERSON_REQUIRED)
            String userId,

            @NotBlank(message = DeliveryRules.MARKET_REQUIRED)
            String market,

            @NotBlank(message = DeliveryRules.VEHICLE)
            @Pattern(regexp = "bike|ebike|car|van", message = DeliveryRules.VEHICLE)
            String vehicle) {}

    record ShiftRequest(
            @NotNull(message = DeliveryRules.SHIFT_TIMES) Instant startsAt,
            @NotNull(message = DeliveryRules.SHIFT_TIMES) Instant endsAt) {}

    record PlanRequest(@Nullable String market) {}

    @GetMapping("/runs")
    ListResponse<RunSummary> runs(
            @RequestParam(required = false) @Nullable String market,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to) {
        var now = clock.instant();
        return new ListResponse<>(console.runs(
                market,
                from == null ? now.minus(Duration.ofDays(1)) : from,
                to == null ? now.plus(Duration.ofDays(2)) : to));
    }

    @GetMapping("/runs/{runId}")
    RunDetail run(@PathVariable String runId) {
        return console.run(runId);
    }

    @PostMapping("/runs/{runId}/assign")
    @RequiresConsole(value = ConsoleScreen.DELIVERY, actions = ConsoleAction.DISPATCH)
    RunSummary assign(@PathVariable String runId, @Valid @RequestBody AssignRequest body, CurrentStaff staff) {
        return console.assign(runId, body.courierId(), actor(staff));
    }

    @GetMapping("/orders/{orderId}")
    @RequiresConsole(ConsoleScreen.ORDERS)
    DeliveryView delivery(@PathVariable String orderId) {
        return console.delivery(orderId);
    }

    @GetMapping("/couriers")
    ListResponse<CourierSummary> couriers(@RequestParam(required = false) @Nullable String market) {
        return new ListResponse<>(console.couriers(market));
    }

    @PostMapping("/couriers")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.DELIVERY, actions = ConsoleAction.DISPATCH)
    CourierSummary addCourier(@Valid @RequestBody CourierRequest body, CurrentStaff staff) {
        return console.addCourier(body.userId(), body.market(), body.vehicle(), actor(staff));
    }

    @PostMapping("/couriers/{courierId}/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.DELIVERY, actions = ConsoleAction.DISPATCH)
    ShiftView addShift(@PathVariable String courierId, @Valid @RequestBody ShiftRequest body, CurrentStaff staff) {
        return console.addShift(courierId, body.startsAt(), body.endsAt(), actor(staff));
    }

    @PostMapping("/plan")
    @RequiresConsole(value = ConsoleScreen.DELIVERY, actions = ConsoleAction.DISPATCH)
    Planned plan(@RequestBody(required = false) @Nullable PlanRequest body, CurrentStaff staff) {
        return console.planNow(body == null ? null : body.market(), actor(staff));
    }

    private static DispatchConsole.Actor actor(CurrentStaff staff) {
        return new DispatchConsole.Actor(staff.userId(), staff.roleCodes());
    }
}
