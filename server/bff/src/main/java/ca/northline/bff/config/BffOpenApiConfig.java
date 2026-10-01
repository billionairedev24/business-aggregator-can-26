package ca.northline.bff.config;

import static ca.northline.openapi.ApiDocs.BFF_SESSION;
import static ca.northline.openapi.ApiDocs.CSRF;

import ca.northline.openapi.ApiDocs;
import ca.northline.openapi.DocsProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.servlet.view.RedirectView;

/**
 * S-125: the BFF's session endpoints as OpenAPI 3.1 — one {@code internal} document, for the Studio (studio-bff),
 * under the {@code consumer} profile for the consumer web (consumer-bff), under {@code console} for the platform console
 * (console-bff, S-90). Everything lives under {@code /bff}
 * ({@code /bff/v3/api-docs}, {@code /bff/swagger-ui.html}, {@code /bff/docs/scalar}, {@code /bff/docs/redoc}),
 * because the edge already routes {@code /bff} of the Studio and consumer hosts to the BFF (S-17), and the hosts' other
 * paths belong to the web apps. The relayed {@code /api/**} is the api's documents (studio, public).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "northline.docs", name = "enabled", matchIfMissing = true)
class BffOpenApiConfig {

    static {
        // /bff/login returns a RedirectView (a 302), not a body: keep Spring's type out of the schemas.
        SpringDocUtils.getConfig().addResponseTypeToIgnore(RedirectView.class);
    }

    @Bean
    GroupedOpenApi bffSessionApi(DocsProperties props, Environment environment) {
        var consumer = environment.acceptsProfiles(Profiles.of("consumer"));
        var console = environment.acceptsProfiles(Profiles.of("console")); // S-90
        var name = consumer ? "consumer-bff" : console ? "console-bff" : "studio-bff";
        var audience = name + " session";
        var cookie = consumer ? "NL_CONSUMER" : console ? "NL_CONSOLE" : "NL_STUDIO";
        return GroupedOpenApi.builder()
                .group("internal")
                .displayName(name + " session API")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch("/bff/**")
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(
                            api,
                            audience,
                            (consumer
                                            ? "The consumer web's backend-for-frontend (S-45). Guests get a session too "
                                                    + "(`guestId`) and browse the api without a token. "
                                            : console
                                                    ? "The platform console's backend-for-frontend (S-90). Only Northline "
                                                            + "staff who signed in with a second factor keep a session. "
                                                    : "The Studio's backend-for-frontend. ")
                                    + "The browser holds only the HttpOnly session cookie; tokens stay in the server-side "
                                    + "session. `/api/**` on the same origin is relayed to the api with the user's access token "
                                    + "(see the api's documents); POST, PUT, PATCH and DELETE need the `X-XSRF-TOKEN` header. "
                                    + "Sign-in starts at `/oauth2/authorization/northline` (or `/bff/login?next=…`).");
                    api.getComponents()
                            .addSecuritySchemes(BFF_SESSION, ApiDocs.bffSession(cookie))
                            .addSecuritySchemes(CSRF, ApiDocs.csrf());
                    api.setSecurity(List.of(ApiDocs.requirement(BFF_SESSION)));
                    logout(api);
                    signedOutAllowed(api);
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    /** The session lookup answers 401 when signed out (200 for a consumer-bff guest); login is a redirect. */
    private static void signedOutAllowed(OpenAPI api) {
        var session = api.getPaths().get("/bff/session");
        if (session != null && session.getGet() != null) {
            session.getGet().setSecurity(List.of());
            session.getGet()
                    .getResponses()
                    .addApiResponse(
                            "401",
                            new ApiResponse()
                                    .description(
                                            "Signed out (the studio-bff). The response still sets the CSRF cookie."));
        }
        var login = api.getPaths().get("/bff/login");
        if (login != null && login.getGet() != null) {
            login.getGet()
                    .security(List.of())
                    .description("Sign-in hand-off after the app's own sign-in at northline-auth: the "
                            + "authorization request comes straight back with a code, and the user lands on `next` "
                            + "(a same-origin path; anything else is replaced by `/`).")
                    .responses(new ApiResponses()
                            .addApiResponse(
                                    "302", new ApiResponse().description("To `/oauth2/authorization/northline`.")));
        }
    }

    /** {@code POST /bff/logout} is Spring Security's logout filter, which springdoc can't see. */
    private static void logout(OpenAPI api) {
        var paths = api.getPaths() == null ? new Paths() : api.getPaths();
        api.setPaths(paths);
        paths.addPathItem(
                "/bff/logout",
                new PathItem()
                        .post(new Operation()
                                .operationId("logout")
                                .summary("Sign out")
                                .description(
                                        "Revokes the refresh token at northline-auth (which ends that sign-in for every client "
                                                + "sharing it, S-20) and drops the session.")
                                .tags(List.of("session"))
                                .security(List.of(ApiDocs.requirement(BFF_SESSION, CSRF)))
                                .responses(new ApiResponses()
                                        .addApiResponse("204", new ApiResponse().description("Signed out."))
                                        .addApiResponse(
                                                "403",
                                                new ApiResponse().description("Missing or wrong CSRF header.")))));
    }
}
