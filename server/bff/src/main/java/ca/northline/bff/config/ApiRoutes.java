package ca.northline.bff.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions.tokenRelay;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * {@code /api/**} → the api with the session's access token as {@code Authorization: Bearer} (TokenRelay, refreshed
 * when needed). The browser's cookies are not forwarded.
 */
@Configuration(proxyBeanMethods = false)
class ApiRoutes {

    @Bean
    RouterFunction<ServerResponse> api(BffProperties props) {
        return route("api")
                .route(RequestPredicates.path("/api/**"), http())
                .before(uri(props.apiUri()))
                .before(removeRequestHeader("Cookie"))
                .filter(tokenRelay())
                .build();
    }
}
