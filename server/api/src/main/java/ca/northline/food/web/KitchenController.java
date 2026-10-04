package ca.northline.food.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.food.application.ComboUseCases.KitchenPromos;
import ca.northline.food.application.ComboUseCases.PromoView;
import ca.northline.food.application.KitchenUseCases.EditKitchenSetup;
import ca.northline.food.application.KitchenUseCases.IdCheckAnswer;
import ca.northline.food.application.KitchenUseCases.KitchenLive;
import ca.northline.food.application.KitchenUseCases.LiveBoard;
import ca.northline.food.application.KitchenUseCases.SetupView;
import ca.northline.food.application.KitchenUseCases.ViewKitchenSetup;
import ca.northline.food.domain.KitchenPromo;
import ca.northline.food.web.KitchenRequests.FulfilmentRequest;
import ca.northline.food.web.KitchenRequests.HolidayRequest;
import ca.northline.food.web.KitchenRequests.HoursRequest;
import ca.northline.food.web.KitchenRequests.PrepRequest;
import ca.northline.food.web.KitchenRequests.PromoRequest;
import ca.northline.restricted.api.HandoffChecks;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live orders (KDS), Hours, prep &amp; capacity, Northline-funded promos — all under {@code /kitchen}. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/kitchen")
@RequiredArgsConstructor
class KitchenController {

    private final KitchenLive live;
    private final ViewKitchenSetup viewSetup;
    private final EditKitchenSetup editSetup;
    private final KitchenPromos promos;

    // ── Live orders ─────────────────────────────────────────────────────────────

    @GetMapping("/live")
    @RequiresMerchant(VIEW)
    LiveBoard board(@PathVariable String merchantId) {
        return live.board(merchantId);
    }

    @PostMapping("/live/{orderId}/accept")
    @RequiresMerchant(OPERATE)
    LiveBoard accept(@PathVariable String merchantId, @PathVariable String orderId, CurrentMember member) {
        return live.accept(merchantId, orderId, member.userId());
    }

    @PostMapping("/live/{orderId}/ready")
    @RequiresMerchant(OPERATE)
    LiveBoard ready(@PathVariable String merchantId, @PathVariable String orderId, CurrentMember member) {
        return live.ready(merchantId, orderId, member.userId());
    }

    @PostMapping("/live/{orderId}/handoff")
    @RequiresMerchant(OPERATE)
    LiveBoard handOff(
            @PathVariable String merchantId,
            @PathVariable String orderId,
            @RequestBody(required = false) @Nullable HandoffRequest body,
            CurrentMember member) {
        return live.handOff(merchantId, orderId, member.userId(), body == null ? null : body.idCheck());
    }

    /** Age-restricted dishes (2026-10-04): the counter's confirmations, for a pickup that needs them. */
    record HandoffRequest(@Nullable IdCheckAnswer idCheck) {}

    record RefuseRequest(
            @NotBlank(message = HandoffChecks.REASON)
            @Pattern(regexp = "no_id|underage|mismatch|nobody_of_age|intoxicated|other", message = HandoffChecks.REASON)
            String reason) {}

    /** Age-restricted dishes: a pickup not handed over at the counter (refund rules: docs/runbooks/age-restricted.md). */
    @PostMapping("/live/{orderId}/refuse")
    @RequiresMerchant(OPERATE)
    LiveBoard refuse(
            @PathVariable String merchantId,
            @PathVariable String orderId,
            @Valid @RequestBody RefuseRequest body,
            CurrentMember member) {
        return live.refuse(merchantId, orderId, member.userId(), body.reason());
    }

    @PostMapping("/prep-bump")
    @RequiresMerchant(OPERATE)
    LiveBoard bump(@PathVariable String merchantId) {
        return live.bumpPrep(merchantId);
    }

    @DeleteMapping("/prep-bump")
    @RequiresMerchant(OPERATE)
    LiveBoard resetBump(@PathVariable String merchantId) {
        return live.resetPrep(merchantId);
    }

    @PostMapping("/pause")
    @RequiresMerchant(OPERATE)
    LiveBoard pause(@PathVariable String merchantId, CurrentMember member) {
        return live.pause(merchantId, member.userId());
    }

    @DeleteMapping("/pause")
    @RequiresMerchant(OPERATE)
    LiveBoard resume(@PathVariable String merchantId, CurrentMember member) {
        return live.resume(merchantId, member.userId());
    }

    // ── Hours, prep & capacity ──────────────────────────────────────────────────

    @GetMapping("/setup")
    @RequiresMerchant(VIEW)
    SetupView setup(@PathVariable String merchantId) {
        return viewSetup.setup(merchantId);
    }

    @PutMapping("/prep")
    @RequiresMerchant(EDIT)
    SetupView prep(@PathVariable String merchantId, @Valid @RequestBody PrepRequest body) {
        return editSetup.prep(merchantId, body.toCommand());
    }

    @PutMapping("/fulfilment")
    @RequiresMerchant(EDIT)
    SetupView fulfilment(@PathVariable String merchantId, @Valid @RequestBody FulfilmentRequest body) {
        return editSetup.fulfilment(merchantId, body.toCommand());
    }

    @PutMapping("/hours")
    @RequiresMerchant(EDIT)
    SetupView hours(@PathVariable String merchantId, @Valid @RequestBody HoursRequest body) {
        return editSetup.hours(
                merchantId,
                body.days().stream().map(KitchenRequests.DayRequest::toCommand).toList());
    }

    @PostMapping("/holiday-hours")
    @RequiresMerchant(EDIT)
    SetupView addHoliday(
            @PathVariable String merchantId, @Valid @RequestBody HolidayRequest body, CurrentMember member) {
        return editSetup.addHoliday(merchantId, body.toCommand(), member.userId());
    }

    @DeleteMapping("/holiday-hours/{holidayId}")
    @RequiresMerchant(EDIT)
    SetupView removeHoliday(@PathVariable String merchantId, @PathVariable String holidayId) {
        return editSetup.removeHoliday(merchantId, holidayId);
    }

    // ── Northline-funded promos ─────────────────────────────────────────────────

    @GetMapping("/promos")
    @RequiresMerchant(VIEW)
    ListResponse<PromoView> promos(@PathVariable String merchantId) {
        return new ListResponse<>(promos.promos(merchantId));
    }

    /** Owner only: these change what the kitchen pays. */
    @PutMapping("/promos/{promo}")
    @RequiresMerchant(MANAGE)
    PromoView setPromo(
            @PathVariable String merchantId,
            @PathVariable String promo,
            @Valid @RequestBody PromoRequest body,
            CurrentMember member) {
        var known = Arrays.stream(KitchenPromo.values()).anyMatch(p -> p.code().equals(promo));
        if (!known) {
            throw new NotFound("promo", promo);
        }
        return promos.set(merchantId, CodedEnum.fromCode(KitchenPromo.class, promo), body.enabled(), member.userId());
    }
}
