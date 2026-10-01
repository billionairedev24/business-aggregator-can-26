package ca.northline.openapi;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * S-33's partner webhook payloads (JSON Schemas under {@code docs/spec/webhooks}, the contract the worker validates
 * every delivery against) as OpenAPI 3.1 {@code webhooks}: one entry per event type, the payload schema as the request
 * body, and the signature headers. The schemas are used as they are, so the reference can't drift from the deliveries.
 */
public final class WebhookDocs {

    /** The signature scheme of the deliveries (the receiver checks it, like an API key it holds the secret of). */
    public static final String SIGNATURE = "webhookSignature";

    private WebhookDocs() {}

    /** Adds every {@code <type>.v<n>.schema.json} under {@code location} (a classpath pattern directory). */
    public static void addWebhooks(OpenAPI openApi, String location) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(location + "/*.schema.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Arrays.stream(resources)
                .sorted(Comparator.comparing(r -> String.valueOf(r.getFilename())))
                .forEach(resource -> add(openApi, resource));
    }

    @SuppressWarnings("rawtypes")
    private static void add(OpenAPI openApi, Resource resource) {
        var file = String.valueOf(resource.getFilename()); // booking.completed.v1.schema.json
        var name = file.substring(0, file.length() - ".schema.json".length()); // booking.completed.v1
        var type = name.substring(0, name.lastIndexOf(".v"));
        Schema schema;
        try {
            schema = Json31.mapper().readValue(resource.getContentAsString(StandardCharsets.UTF_8), Schema.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable webhook schema " + file, e);
        }
        schema.set$schema(null);
        schema.set$id(null);
        var schemaName = schemaName(name);
        openApi.getComponents().addSchemas(schemaName, schema);
        openApi.getComponents()
                .addSecuritySchemes(
                        SIGNATURE,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Northline-Signature")
                                .description(
                                        "Northline signs each delivery: `t=<unix seconds>,v1=<hex HMAC-SHA256 of \"<t>.<raw "
                                                + "body>\" keyed with the endpoint's whsec_… secret>`. Your endpoint verifies it."));
        var operation = new Operation()
                .operationId("webhook_" + name.replace('.', '_'))
                .summary(type)
                .description(schema.getDescription() + "\n\nSent as `POST` to each endpoint subscribed to `" + type
                        + "` in Studio › Settings › API. Verify `Northline-Signature` (HMAC-SHA256 of "
                        + "`<t>.<raw body>` with the endpoint's `whsec_…` secret; reject timestamps older than 5 "
                        + "minutes) and deduplicate on the event `id`. Any 2xx acknowledges; everything else is "
                        + "retried with back-off for about 3 days (docs/runbooks/webhooks.md).")
                .tags(java.util.List.of("webhooks"))
                .security(java.util.List.of(ApiDocs.requirement(SIGNATURE)))
                .addParametersItem(header(
                        "Northline-Signature",
                        "`t=<unix seconds>,v1=<hex HMAC-SHA256>` — one "
                                + "`v1` per valid secret while a rotated secret overlaps.",
                        "t=1790790312,v1=5257a869e7…"))
                .addParametersItem(header(
                        "Northline-Event-Id",
                        "Event id (ULID), the same on every retry.",
                        "01J9ZD3V00000000000000EVT1"))
                .addParametersItem(header("Northline-Event-Type", "The event type.", type))
                .addParametersItem(header(
                        "Northline-Delivery-Id",
                        "This delivery (a resend gets a new one).",
                        "01J9ZD3V00000000000000DLV1"))
                .addParametersItem(header("Northline-Delivery-Attempt", "1 for the first attempt.", "1"))
                .requestBody(new RequestBody()
                        .required(true)
                        .content(new Content()
                                .addMediaType("application/json", new MediaType().schema(ApiDocs.ref(schemaName)))))
                .responses(new ApiResponses()
                        .addApiResponse("2XX", new ApiResponse().description("Received. The body is ignored."))
                        .addApiResponse(
                                "default",
                                new ApiResponse()
                                        .description(
                                                "Anything else (and timeouts, TLS errors, redirects) is retried.")));
        openApi.addWebhooks(type, new PathItem().post(operation));
    }

    private static HeaderParameter header(String name, String description, String example) {
        return (HeaderParameter) new HeaderParameter()
                .name(name)
                .required(true)
                .description(description)
                .schema(new StringSchema())
                .example(example);
    }

    /** booking.completed.v1 → BookingCompletedWebhookV1. */
    private static String schemaName(String name) {
        var out = new StringBuilder();
        var parts = name.split("[._]");
        for (var i = 0; i < parts.length - 1; i++) {
            out.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(parts[i].substring(1));
        }
        return out.append("Webhook")
                .append(parts[parts.length - 1].toUpperCase(Locale.ROOT))
                .toString();
    }
}
