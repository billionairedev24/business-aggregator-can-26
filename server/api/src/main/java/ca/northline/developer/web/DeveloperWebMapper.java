package ca.northline.developer.web;

import ca.northline.developer.domain.ApiKey;
import ca.northline.developer.domain.AuditRecord;
import ca.northline.developer.domain.WebhookDelivery;
import ca.northline.developer.domain.WebhookEndpoint;
import ca.northline.developer.web.DeveloperDtos.ApiKeyResponse;
import ca.northline.developer.web.DeveloperDtos.AuditEntryResponse;
import ca.northline.developer.web.DeveloperDtos.WebhookAttemptResponse;
import ca.northline.developer.web.DeveloperDtos.WebhookDeliveryResponse;
import ca.northline.developer.web.DeveloperDtos.WebhookResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface DeveloperWebMapper {

    ApiKeyResponse toResponse(ApiKey key);

    List<ApiKeyResponse> toKeyResponses(List<ApiKey> keys);

    @Mapping(target = "signature", constant = WebhookEndpoint.SIGNATURE)
    WebhookResponse toResponse(WebhookEndpoint endpoint);

    List<WebhookResponse> toWebhookResponses(List<WebhookEndpoint> endpoints);

    WebhookDeliveryResponse toResponse(WebhookDelivery delivery);

    List<WebhookDeliveryResponse> toDeliveryResponses(List<WebhookDelivery> deliveries);

    WebhookAttemptResponse toResponse(WebhookDelivery.Attempt attempt);

    @Mapping(target = "actorName", source = "actorName")
    AuditEntryResponse toResponse(AuditRecord record, @Nullable String actorName);
}
