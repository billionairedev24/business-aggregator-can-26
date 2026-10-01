package ca.northline.developer.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.developer.application.DeveloperUseCases.Actor;
import ca.northline.developer.application.PublishableKeys;
import ca.northline.developer.domain.DeveloperRules;
import ca.northline.developer.domain.PublishableKey;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-76 publishable key for the website embed (Settings › API › "Embed your store", Business page › Embed code).
 *
 * <pre>
 * GET  /api/v1/merchants/{merchantId}/settings/publishable-key           the active key; 204 when none     (VIEW)
 * POST /api/v1/merchants/{merchantId}/settings/publishable-key           issue, or roll (old key stops)    (MANAGE)
 * PUT  /api/v1/merchants/{merchantId}/settings/publishable-key/origins   {allowedOrigins} sites it answers on (MANAGE)
 * </pre>
 *
 * {@code scriptUrl} is the embed script on the consumer site ({@code CONSUMER_ORIGIN}/embed.js).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/settings/publishable-key")
class PublishableKeyController {

    private final PublishableKeys keys;
    private final String scriptUrl;

    PublishableKeyController(
            PublishableKeys keys, @Value("${northline.developer.embed.site-origin}") String siteOrigin) {
        this.keys = keys;
        this.scriptUrl = siteOrigin.replaceAll("/+$", "") + "/embed.js";
    }

    record PublishableKeyResponse(String key, List<String> allowedOrigins, Instant createdAt, String scriptUrl) {}

    record RollRequest(@Nullable List<String> allowedOrigins) {}

    record OriginsRequest(
            @NotNull(message = DeveloperRules.ORIGIN_FORMAT) List<String> allowedOrigins) {}

    @GetMapping
    @RequiresMerchant(VIEW)
    ResponseEntity<PublishableKeyResponse> get(@PathVariable String merchantId) {
        return keys.current(merchantId)
                .map(k -> ResponseEntity.ok(response(k)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    @RequiresMerchant(MANAGE)
    PublishableKeyResponse roll(
            @PathVariable String merchantId,
            @RequestBody(required = false) @Nullable RollRequest body,
            CurrentMember member) {
        return response(keys.roll(actor(member), body == null ? null : body.allowedOrigins()));
    }

    @PutMapping("/origins")
    @RequiresMerchant(MANAGE)
    PublishableKeyResponse origins(
            @PathVariable String merchantId, @Valid @RequestBody OriginsRequest body, CurrentMember member) {
        return response(keys.allowOrigins(actor(member), body.allowedOrigins()));
    }

    private PublishableKeyResponse response(PublishableKey k) {
        return new PublishableKeyResponse(k.key(), k.allowedOrigins(), k.createdAt(), scriptUrl);
    }

    private static Actor actor(CurrentMember member) {
        return new Actor(member.merchantId(), member.userId(), member.role().code());
    }
}
