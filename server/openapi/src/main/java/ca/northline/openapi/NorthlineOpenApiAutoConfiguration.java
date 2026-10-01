package ca.northline.openapi;

import java.util.List;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * S-125: the shared OpenAPI setup of the api, northline-auth and the BFF. Off with {@code northline.docs.enabled=false}
 * (the production profiles, which also turn springdoc, Swagger UI and Scalar off).
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(GroupedOpenApi.class)
@ConditionalOnProperty(prefix = "northline.docs", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(DocsProperties.class)
public class NorthlineOpenApiAutoConfiguration {

    /**
     * Stable, readable operation ids: {@code <controller without "Controller"><Method>}, e.g. {@code listingList} —
     * springdoc's default ({@code list_5}) renumbers whenever another controller adds a {@code list} method, which would
     * churn the committed specs and every generated client.
     */
    @Bean
    GlobalOperationCustomizer northlineOperationIds() {
        return (operation, handler) -> {
            var type = handler.getBeanType().getSimpleName().replaceFirst("(Rest)?Controller$", "");
            var method = handler.getMethod().getName();
            operation.setOperationId(Character.toLowerCase(type.charAt(0))
                    + type.substring(1)
                    + Character.toUpperCase(method.charAt(0))
                    + method.substring(1));
            return operation;
        };
    }

    @Bean
    RouterFunction<ServerResponse> northlineDocsPages(
            DocsProperties props, ObjectProvider<List<GroupedOpenApi>> groups, Environment environment) {
        return new DocsPagesController(props, groups, environment).routes();
    }
}
