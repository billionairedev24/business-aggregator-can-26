package ca.northline.developer.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.developer.application.DeveloperUseCases.Actor;
import ca.northline.developer.application.DeveloperUseCases.AddWebhookEndpoint;
import ca.northline.developer.application.DeveloperUseCases.EnableWebhookEndpoint;
import ca.northline.developer.application.DeveloperUseCases.IssueApiKey;
import ca.northline.developer.application.DeveloperUseCases.ListApiKeys;
import ca.northline.developer.application.DeveloperUseCases.ListWebhookDeliveries;
import ca.northline.developer.application.DeveloperUseCases.ListWebhookEndpoints;
import ca.northline.developer.application.DeveloperUseCases.RemoveWebhookEndpoint;
import ca.northline.developer.application.DeveloperUseCases.ResendWebhookDelivery;
import ca.northline.developer.application.DeveloperUseCases.RevokeApiKey;
import ca.northline.developer.application.DeveloperUseCases.RotateWebhookSecret;
import ca.northline.developer.application.DeveloperUseCases.SendTestWebhook;
import ca.northline.developer.application.DeveloperUseCases.ViewAuditLog;
import ca.northline.developer.domain.DeveloperRules;
import ca.northline.developer.web.DeveloperDtos.AddWebhookRequest;
import ca.northline.developer.web.DeveloperDtos.ApiKeyResponse;
import ca.northline.developer.web.DeveloperDtos.AuditEntryResponse;
import ca.northline.developer.web.DeveloperDtos.DeveloperOptionsResponse;
import ca.northline.developer.web.DeveloperDtos.IssueApiKeyRequest;
import ca.northline.developer.web.DeveloperDtos.IssuedApiKeyResponse;
import ca.northline.developer.web.DeveloperDtos.RotateWebhookRequest;
import ca.northline.developer.web.DeveloperDtos.WebhookDeliveryResponse;
import ca.northline.developer.web.DeveloperDtos.WebhookResponse;
import ca.northline.developer.web.DeveloperDtos.WebhookWithSecretResponse;
import ca.northline.identity.api.PersonDirectory;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings › API &amp; integrations and Settings › Security › Audit log:
 *
 * <pre>
 * GET    /api/v1/merchants/{merchantId}/settings/developer-options      scopes + events to pick from   (VIEW)
 * GET    /api/v1/merchants/{merchantId}/settings/api-keys               active keys                    (VIEW)
 * POST   /api/v1/merchants/{merchantId}/settings/api-keys               {name, scopes} → key + secret  (MANAGE)
 * DELETE /api/v1/merchants/{merchantId}/settings/api-keys/{keyId}       revoke                         (MANAGE)
 * GET    /api/v1/merchants/{merchantId}/settings/webhooks                                              (VIEW)
 * POST   /api/v1/merchants/{merchantId}/settings/webhooks               {url, events} → + secret       (MANAGE)
 * POST   /api/v1/merchants/{merchantId}/settings/webhooks/{id}/secret   {overlapHours?} rotate → secret (MANAGE)
 * POST   /api/v1/merchants/{merchantId}/settings/webhooks/{id}/enable   back on after auto-disable     (MANAGE)
 * GET    /api/v1/merchants/{merchantId}/settings/webhooks/{id}/deliveries          delivery log         (VIEW)
 * POST   /api/v1/merchants/{merchantId}/settings/webhooks/{id}/deliveries/{d}/resend → queued          (MANAGE)
 * POST   /api/v1/merchants/{merchantId}/settings/webhooks/{id}/test     webhook.test → queued          (MANAGE)
 * DELETE /api/v1/merchants/{merchantId}/settings/webhooks/{id}                                         (MANAGE)
 * GET    /api/v1/merchants/{merchantId}/settings/audit-log              last 90 days                   (MANAGE)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/settings")
@RequiredArgsConstructor
class DeveloperSettingsController {

    private final ListApiKeys listKeys;
    private final IssueApiKey issueKey;
    private final RevokeApiKey revokeKey;
    private final ListWebhookEndpoints listWebhooks;
    private final AddWebhookEndpoint addWebhook;
    private final RotateWebhookSecret rotateWebhook;
    private final RemoveWebhookEndpoint removeWebhook;
    private final EnableWebhookEndpoint enableWebhook;
    private final ListWebhookDeliveries deliveries;
    private final ResendWebhookDelivery resend;
    private final SendTestWebhook sendTest;
    private final ViewAuditLog auditLog;
    private final PersonDirectory people;
    private final DeveloperWebMapper mapper;

    @GetMapping("/developer-options")
    @RequiresMerchant(VIEW)
    DeveloperOptionsResponse options(@PathVariable String merchantId) {
        return new DeveloperOptionsResponse(DeveloperRules.SCOPES, DeveloperRules.EVENTS);
    }

    @GetMapping("/api-keys")
    @RequiresMerchant(VIEW)
    ListResponse<ApiKeyResponse> keys(@PathVariable String merchantId) {
        return new ListResponse<>(mapper.toKeyResponses(listKeys.list(merchantId)));
    }

    @PostMapping("/api-keys")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    IssuedApiKeyResponse issue(
            @PathVariable String merchantId, @Valid @RequestBody IssueApiKeyRequest body, CurrentMember member) {
        var issued = issueKey.issue(new IssueApiKey.Command(actor(member), body.name(), body.scopes()));
        return new IssuedApiKeyResponse(mapper.toResponse(issued.key()), issued.secret());
    }

    @DeleteMapping("/api-keys/{keyId}")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable String merchantId, @PathVariable String keyId, CurrentMember member) {
        revokeKey.revoke(actor(member), keyId);
    }

    @GetMapping("/webhooks")
    @RequiresMerchant(VIEW)
    ListResponse<WebhookResponse> webhooks(@PathVariable String merchantId) {
        return new ListResponse<>(mapper.toWebhookResponses(listWebhooks.endpoints(merchantId)));
    }

    @PostMapping("/webhooks")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    WebhookWithSecretResponse addWebhook(
            @PathVariable String merchantId, @Valid @RequestBody AddWebhookRequest body, CurrentMember member) {
        var added = addWebhook.add(new AddWebhookEndpoint.Command(actor(member), body.url(), body.events()));
        return new WebhookWithSecretResponse(mapper.toResponse(added.endpoint()), added.secret());
    }

    @PostMapping("/webhooks/{endpointId}/secret")
    @RequiresMerchant(MANAGE)
    WebhookWithSecretResponse rotate(
            @PathVariable String merchantId,
            @PathVariable String endpointId,
            @RequestBody(required = false) @Nullable RotateWebhookRequest body,
            CurrentMember member) {
        var rotated = rotateWebhook.rotate(actor(member), endpointId, body == null ? null : body.overlapHours());
        return new WebhookWithSecretResponse(mapper.toResponse(rotated.endpoint()), rotated.secret());
    }

    @PostMapping("/webhooks/{endpointId}/enable")
    @RequiresMerchant(MANAGE)
    WebhookResponse enable(@PathVariable String merchantId, @PathVariable String endpointId, CurrentMember member) {
        return mapper.toResponse(enableWebhook.enable(actor(member), endpointId));
    }

    @GetMapping("/webhooks/{endpointId}/deliveries")
    @RequiresMerchant(VIEW)
    ListResponse<WebhookDeliveryResponse> deliveries(@PathVariable String merchantId, @PathVariable String endpointId) {
        return new ListResponse<>(mapper.toDeliveryResponses(deliveries.deliveries(merchantId, endpointId)));
    }

    @PostMapping("/webhooks/{endpointId}/deliveries/{deliveryId}/resend")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    WebhookDeliveryResponse resend(
            @PathVariable String merchantId,
            @PathVariable String endpointId,
            @PathVariable String deliveryId,
            CurrentMember member) {
        return mapper.toResponse(resend.resend(actor(member), endpointId, deliveryId));
    }

    @PostMapping("/webhooks/{endpointId}/test")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    WebhookDeliveryResponse test(
            @PathVariable String merchantId, @PathVariable String endpointId, CurrentMember member) {
        return mapper.toResponse(sendTest.sendTest(actor(member), endpointId));
    }

    @DeleteMapping("/webhooks/{endpointId}")
    @RequiresMerchant(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeWebhook(@PathVariable String merchantId, @PathVariable String endpointId, CurrentMember member) {
        removeWebhook.remove(actor(member), endpointId);
    }

    @GetMapping("/audit-log")
    @RequiresMerchant(MANAGE)
    ListResponse<AuditEntryResponse> audit(@PathVariable String merchantId) {
        var records = auditLog.recent(merchantId);
        var names = people.people(
                records.stream().map(r -> r.actorId()).filter(Objects::nonNull).toList());
        return new ListResponse<>(records.stream()
                .map(r -> {
                    var person = r.actorId() == null ? null : names.get(r.actorId());
                    return mapper.toResponse(r, person == null ? null : person.displayName());
                })
                .toList());
    }

    private static Actor actor(CurrentMember member) {
        return new Actor(member.merchantId(), member.userId(), member.role().code());
    }
}
