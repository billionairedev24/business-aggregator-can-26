package ca.northline.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Applies the Northline conventions to every operation of every group, so each controller needs no annotations for
 * them: {@code *Id} path parameters are ULIDs; an operation that takes a body answers 422 {@link ApiDocs#VALIDATION_ERRORS};
 * secured operations answer 401 and 403 {@link ApiDocs#PROBLEM}; operations with path parameters answer 404; and
 * {@code *Cents} properties are {@link ApiDocs#MONEY_CENTS}. Existing responses are never replaced. Added last to each
 * group ({@link ApiDocs#conventions()}), after the group has set its security.
 */
final class ApiConventions implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() != null) {
            openApi.getPaths()
                    .forEach((path, item) -> item.readOperationsMap()
                            .forEach((method, operation) -> apply(openApi, path, method, operation)));
        }
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(ApiConventions::money);
        }
        pruneUnusedShared(openApi);
    }

    /**
     * Every group starts with all shared schemas and the app's security schemes; drop the ones this document never
     * references, so each spec lists only what it uses (Redocly's no-unused-components).
     */
    private static void pruneUnusedShared(OpenAPI openApi) {
        var components = openApi.getComponents();
        if (components == null) {
            return;
        }
        String json;
        try {
            json = Json31.mapper().writeValueAsString(openApi);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        // Schemas reference each other (ValidationErrors → ValidationError): repeat until nothing changes.
        var removed = true;
        while (removed && components.getSchemas() != null) {
            removed = false;
            for (var name : SHARED_SCHEMAS) {
                if (components.getSchemas().containsKey(name)
                        && !json.contains("\"#/components/schemas/" + name + "\"")) {
                    components.getSchemas().remove(name);
                    removed = true;
                }
            }
            try {
                json = Json31.mapper().writeValueAsString(openApi);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        if (components.getSecuritySchemes() != null) {
            var used = new java.util.HashSet<String>();
            if (openApi.getSecurity() != null) {
                openApi.getSecurity().forEach(r -> used.addAll(r.keySet()));
            }
            if (openApi.getPaths() != null) {
                openApi.getPaths()
                        .values()
                        .forEach(item -> item.readOperations().forEach(o -> {
                            if (o.getSecurity() != null) {
                                o.getSecurity().forEach(r -> used.addAll(r.keySet()));
                            }
                        }));
            }
            if (openApi.getWebhooks() != null) {
                openApi.getWebhooks()
                        .values()
                        .forEach(item -> item.readOperations().forEach(o -> {
                            if (o.getSecurity() != null) {
                                o.getSecurity().forEach(r -> used.addAll(r.keySet()));
                            }
                        }));
            }
            components.getSecuritySchemes().keySet().removeIf(name -> !used.contains(name));
        }
    }

    private static final java.util.List<String> SHARED_SCHEMAS = java.util.List.of(
            ApiDocs.VALIDATION_ERRORS, ApiDocs.VALIDATION_ERROR, ApiDocs.PROBLEM, ApiDocs.ULID, ApiDocs.MONEY_CENTS);

    private static void apply(OpenAPI openApi, String path, PathItem.HttpMethod method, Operation operation) {
        if (operation.getParameters() != null) {
            operation.getParameters().forEach(ApiConventions::ulid);
        }
        var responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        operation.setResponses(responses);
        var secured = operation.getSecurity() == null
                ? openApi.getSecurity() != null && !openApi.getSecurity().isEmpty()
                : !operation.getSecurity().isEmpty();
        if (secured) {
            responses.putIfAbsent("401", ApiDocs.problem("Not signed in, or the token expired.", "unauthorized"));
            responses.putIfAbsent("403", ApiDocs.problem("Your sign-in doesn't allow this.", "forbidden"));
        }
        if (path.contains("{")) {
            responses.putIfAbsent("404", ApiDocs.problem("Not found.", "not_found"));
        }
        var writes = method == PathItem.HttpMethod.POST
                || method == PathItem.HttpMethod.PUT
                || method == PathItem.HttpMethod.PATCH;
        if (writes && operation.getRequestBody() != null) {
            responses.putIfAbsent("422", ApiDocs.validationErrors());
        }
    }

    private static void ulid(Parameter parameter) {
        if ("path".equals(parameter.getIn())
                && parameter.getName() != null
                && parameter.getName().endsWith("Id")
                && parameter.getSchema() != null
                && "string".equals(type(parameter.getSchema()))) {
            parameter.setSchema(ApiDocs.ref(ApiDocs.ULID));
        }
    }

    @SuppressWarnings("rawtypes")
    private static void money(Schema schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        properties.forEach((name, property) -> {
            if (name.endsWith("Cents") && "integer".equals(type(property)) && property.get$ref() == null) {
                property.setFormat("int64");
                if (property.getDescription() == null) {
                    property.setDescription("Cents of Canadian dollars (CAD).");
                }
            }
        });
    }

    private static String type(Schema<?> schema) {
        if (schema.getType() != null) {
            return schema.getType();
        }
        var types = schema.getTypes();
        return types == null || types.isEmpty() ? "" : types.iterator().next();
    }
}
