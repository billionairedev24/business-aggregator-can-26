package ca.northline.trust.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.trust.api.ActiveRewards;
import ca.northline.trust.application.ManageReward;
import ca.northline.trust.domain.MerchantReward;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-75 provider-funded reward.
 *
 * <pre>
 * GET /api/v1/merchants/{merchantId}/reward          the business's reward, 204 when it never set one (VIEW)
 * PUT /api/v1/merchants/{merchantId}/reward          switch on (with its terms) or off (MANAGE: the owner pays)
 * GET /api/v1/public/merchants/{id}/reward           what runs today, for the public page; 204 when none
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class RewardController {

    private final ManageReward manage;
    private final ActiveRewards running;

    record RewardRequest(
            @NotNull(message = MerchantReward.ACTIVE_REQUIRED)
            Boolean active,

            @Nullable Integer multiplier,

            @Nullable @Size(max = MerchantReward.LABEL_MAX, message = MerchantReward.LABEL_TOO_LONG)
            String label,

            @Nullable LocalDate endsOn) {}

    record RewardResponse(
            boolean active, int multiplier, @Nullable String label, LocalDate endsOn, boolean running) {}

    @GetMapping("/api/v1/merchants/{merchantId}/reward")
    @RequiresMerchant(VIEW)
    ResponseEntity<RewardResponse> get(@PathVariable String merchantId) {
        var isRunning = running.running(merchantId).isPresent();
        return manage.of(merchantId)
                .map(r -> ResponseEntity.ok(response(r, isRunning)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/api/v1/merchants/{merchantId}/reward")
    @RequiresMerchant(MANAGE)
    RewardResponse put(@PathVariable String merchantId, @Valid @RequestBody RewardRequest body, CurrentMember member) {
        var saved = manage.save(new ManageReward.Command(
                merchantId,
                member.userId(),
                member.role().code(),
                body.active(),
                body.multiplier() == null ? 0 : body.multiplier(),
                body.label(),
                body.endsOn()));
        return response(saved, running.running(merchantId).isPresent());
    }

    // `{id}`, not `{merchantId}`: a `{merchantId}` path is a member endpoint (MerchantAccessInterceptor)
    @GetMapping("/api/v1/public/merchants/{id}/reward")
    ResponseEntity<ActiveRewards.Reward> publicReward(@PathVariable String id) {
        return running.running(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static RewardResponse response(MerchantReward r, boolean isRunning) {
        return new RewardResponse(r.active(), r.multiplier(), r.label(), r.endsOn(), isRunning);
    }
}
