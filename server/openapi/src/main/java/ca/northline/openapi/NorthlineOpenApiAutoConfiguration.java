package ca.northline.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

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

    /** Info, servers (fixed, so the committed specs don't depend on the request) and the shared schemas. */
    @Bean
    @ConditionalOnMissingBean
    OpenAPI northlineOpenApi(DocsProperties props) {
        var components = new Components();
        ApiDocs.addSharedSchemas(components);
        return new OpenAPI()
                .info(new Info()
                        .title(props.title())
                        .version("v1")
                        .description(props.description())
                        .contact(new Contact().name("Northline").url("https://northline.ca"))
                        .license(new License().name("Proprietary").identifier("LicenseRef-Northline")))
                .servers(props.servers().stream()
                        .map(url -> new Server().url(url))
                        .toList())
                .components(components);
    }

    @Bean
    DocsPagesController northlineDocsPages(
            DocsProperties props, ObjectProvider<List<GroupedOpenApi>> groups, Environment environment) {
        return new DocsPagesController(props, groups, environment);
    }
}
