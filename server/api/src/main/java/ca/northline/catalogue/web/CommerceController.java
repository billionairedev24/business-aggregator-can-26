package ca.northline.catalogue.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.catalogue.application.IntegrationRepository.Connection;
import ca.northline.catalogue.application.StockSync;
import ca.northline.catalogue.application.SyncIntegrations;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.ListResponse;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopify / Square / Lightspeed connections under {@code /listings/integrations} (S-35): list, connect (OAuth: answers
 * the platform's consent page, the browser goes there), disconnect (owner), sync now. S-158 two-way stock:
 *
 * <pre>
 * GET  …/{provider}/stock                        StockSync.Status (location, counts, the changes that need you)
 * GET  …/{provider}/locations                    {items: [{id, name, chosen}]}   (read live from the platform)
 * PUT  …/{provider}/location {locationId}        StockSync.Status   owner; 422 locationId
 * POST …/stock-changes/{pushId}/retry            PushView           409 push_applied | push_closed
 * POST …/stock-changes/{pushId}/dismiss          PushView           409 push_closed
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/listings/integrations")
@RequiredArgsConstructor
class CommerceController {

    private final SyncIntegrations integrations;
    private final StockSync stock;

    record LocationRequest(
            @NotBlank(message = StockSync.LOCATION_UNKNOWN) String locationId) {}

    record SyncErrorResponse(String externalId, String title, String error) {}

    /**
     * @param state {@code "connected"}, {@code "reconnect"} (grant revoked) or, when not connected, {@code "disconnected"}
     * @param syncStatus {@code importing} | {@code ok} | {@code failed}
     * @param updates {@code webhooks} (changes arrive as they happen, plus a daily full read) | {@code hourly}
     * @param lastSyncCount listings whose price or stock the last full sync changed
     */
    record ConnectionResponse(
            CommerceProvider provider,
            boolean available,
            boolean connected,
            String state,
            @Nullable String accountLabel,
            @Nullable Instant connectedAt,
            @Nullable Instant lastSyncAt,
            @Nullable Integer lastSyncCount,
            int createdCount,
            int hiddenCount,
            List<SyncErrorResponse> errors,
            @Nullable String syncStatus,
            String updates,
            @Nullable String locationId,
            @Nullable String locationName) {

        static ConnectionResponse of(Connection c) {
            var status = c.syncStatus();
            return new ConnectionResponse(
                    c.provider(),
                    c.available(),
                    c.connected(),
                    !c.connected() ? "disconnected" : c.needsReconnect() ? "reconnect" : "connected",
                    c.accountLabel(),
                    c.connectedAt(),
                    c.lastSyncAt(),
                    c.lastSyncCount(),
                    c.createdCount(),
                    c.hiddenCount(),
                    c.errors().stream()
                            .map(e -> new SyncErrorResponse(e.externalId(), e.title(), e.error()))
                            .toList(),
                    status == null ? null : status.name().toLowerCase(Locale.ROOT),
                    webhooks(c),
                    c.locationId(),
                    c.locationName());
        }

        private static String webhooks(Connection c) {
            return switch (c.webhooks()) {
                case ACTIVE -> "webhooks";
                case NONE, FAILED -> "hourly";
            };
        }
    }

    /** {@code shop}: the Shopify store ({@code your-store} or {@code your-store.myshopify.com}); ignored otherwise. */
    record ConnectRequest(@Nullable String shop) {}

    record ConnectResponse(String authorizationUrl) {}

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<ConnectionResponse> connections(@PathVariable String merchantId) {
        return new ListResponse<>(integrations.connections(merchantId).stream()
                .map(ConnectionResponse::of)
                .toList());
    }

    @PostMapping("/{provider}/connect")
    @RequiresMerchant(MANAGE)
    ConnectResponse connect(
            @PathVariable String merchantId,
            @PathVariable String provider,
            @RequestBody(required = false) @Nullable ConnectRequest body,
            CurrentMember member) {
        var url = integrations.connect(
                merchantId, member.userId(), provider(provider), body == null ? null : body.shop());
        return new ConnectResponse(url.toString());
    }

    @PostMapping("/{provider}/disconnect")
    @RequiresMerchant(MANAGE)
    ConnectionResponse disconnect(@PathVariable String merchantId, @PathVariable String provider) {
        return ConnectionResponse.of(integrations.disconnect(merchantId, provider(provider)));
    }

    @PostMapping("/{provider}/sync")
    @RequiresMerchant(EDIT)
    ConnectionResponse sync(@PathVariable String merchantId, @PathVariable String provider) {
        return ConnectionResponse.of(integrations.sync(merchantId, provider(provider)));
    }

    @GetMapping("/{provider}/stock")
    @RequiresMerchant(VIEW)
    StockSync.Status stock(@PathVariable String merchantId, @PathVariable String provider) {
        return stock.status(merchantId, provider(provider));
    }

    @GetMapping("/{provider}/locations")
    @RequiresMerchant(MANAGE)
    ListResponse<StockSync.LocationView> locations(@PathVariable String merchantId, @PathVariable String provider) {
        return new ListResponse<>(stock.locations(merchantId, provider(provider)));
    }

    @PutMapping("/{provider}/location")
    @RequiresMerchant(MANAGE)
    StockSync.Status location(
            @PathVariable String merchantId,
            @PathVariable String provider,
            @Valid @RequestBody LocationRequest body,
            CurrentMember member) {
        return stock.chooseLocation(
                merchantId,
                provider(provider),
                body.locationId(),
                member.userId(),
                member.role().code());
    }

    @PostMapping("/stock-changes/{pushId}/retry")
    @RequiresMerchant(EDIT)
    StockSync.PushView retry(@PathVariable String merchantId, @PathVariable String pushId, CurrentMember member) {
        return stock.retry(merchantId, pushId, member.userId());
    }

    @PostMapping("/stock-changes/{pushId}/dismiss")
    @RequiresMerchant(EDIT)
    StockSync.PushView dismiss(@PathVariable String merchantId, @PathVariable String pushId, CurrentMember member) {
        return stock.dismiss(merchantId, pushId, member.userId());
    }

    static CommerceProvider provider(String code) {
        try {
            return CodedEnum.fromCode(CommerceProvider.class, code);
        } catch (IllegalArgumentException ex) {
            throw new NotFound("integration", code);
        }
    }
}
