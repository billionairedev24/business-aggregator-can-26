package ca.northline.bff.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions.tokenRelay;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * {@code /api/**} → the api with the session's access token as {@code Authorization: Bearer} (TokenRelay, refreshed
 * when needed). The browser's cookies, its own {@code Authorization} and any {@code X-Northline-Guest} /
 * {@code X-Dev-User} it sends are not forwarded. S-45: with {@code northline.bff.guests} a request without a signed-in
 * session goes on without a token (the api answers its public endpoints and 401 for the rest), and the session's guest
 * id is added as {@value Guests#HEADER}. S-135: a body is forwarded only when the browser sent one ({@link RelayBody}).
 */
@Configuration(proxyBeanMethods = false)
class ApiRoutes {

    @Bean
    RouterFunction<ServerResponse> api(BffProperties props) {
        var route = route("api")
                .route(RequestPredicates.path("/api/**"), http())
                .before(RelayBody::lookAhead)
                .before(uri(props.apiUri()))
                .before(removeRequestHeader(HttpHeaders.COOKIE))
                .before(removeRequestHeader(HttpHeaders.AUTHORIZATION))
                .before(removeRequestHeader(Guests.HEADER))
                .before(removeRequestHeader("X-Dev-User"));
        if (props.guests()) {
            route = route.before(ApiRoutes::guestHeader);
        }
        return route.filter(tokenRelay()).build();
    }

    private static ServerRequest guestHeader(ServerRequest request) {
        var guest = Guests.existing(request.servletRequest());
        return guest == null
                ? request
                : ServerRequest.from(request)
                        .headers(h -> h.set(Guests.HEADER, guest))
                        .build();
    }
}
