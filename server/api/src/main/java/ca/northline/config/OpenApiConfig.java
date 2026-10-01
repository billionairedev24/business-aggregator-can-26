package ca.northline.config;

import static ca.northline.openapi.ApiDocs.BFF_SESSION;
import static ca.northline.openapi.ApiDocs.CSRF;
import static ca.northline.openapi.ApiDocs.DPOP;
import static ca.northline.openapi.ApiDocs.DPOP_PROOF;
import static ca.northline.openapi.ApiDocs.OAUTH2;
import static ca.northline.openapi.ApiDocs.PARTNER;

import ca.northline.openapi.ApiDocs;
import ca.northline.openapi.DocsProperties;
import ca.northline.openapi.WebhookDocs;
import ca.northline.shared.security.PartnerAccess;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import java.util.function.Consumer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S-125: the api's OpenAPI documents, one per audience (docs/runbooks/api-docs.md). Every path belongs to at least one
 * group ({@code OpenApiSpecsTest} checks it), so nothing is undocumented:
 *
 * <ul>
 *   <li>{@code public} — the consumer web and apps: public reads (no sign-in) and the signed-in customer's endpoints;
 *   <li>{@code studio} — the business Studio ({@code /api/v1/merchants/**}, businesses, onboarding, invitations);
 *   <li>{@code partner} — exactly the handlers marked {@link PartnerAccess} (S-30), with their scope;
 *   <li>{@code console} — staff ({@code /api/v1/console/**}, role STAFF + acr=mfa);
 *   <li>{@code webhooks} — the partner webhook payloads (S-33) as OpenAPI 3.1 {@code webhooks};
 *   <li>{@code internal} — provider callbacks (Stripe, calendars, commerce), email links and the local dev tools.
 * </ul>
 *
 * Production publishes none of them from the api ({@code application-prod.yml}); the docs site (S-126) publishes
 * {@code public}, {@code partner} and {@code webhooks} from the committed files.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "northline.docs", name = "enabled", matchIfMissing = true)
class OpenApiConfig {

    static final String[] PUBLIC_READS = {
        "/api/v1/public/**", "/api/v1/storefronts/**", "/api/v1/search/**", "/api/v1/geo/**"
    };
    static final String[] CUSTOMER = {"/api/v1/me", "/api/v1/me/**", "/api/v1/cart", "/api/v1/cart/**"};
    static final String[] STUDIO = {
        "/api/v1/merchants/**",
        "/api/v1/me",
        "/api/v1/me/businesses",
        "/api/v1/onboarding/**",
        "/api/v1/team-invitations/**",
        "/api/v1/ai/**"
    };
    static final String[] CONSOLE = {"/api/v1/console/**"};
    static final String[] INTERNAL = {
        "/api/v1/webhooks/**",
        "/api/v1/commerce/oauth/**",
        "/api/v1/calendar/oauth/**",
        "/api/v1/email/**",
        "/api/v1/dev/**"
    };

    @Bean
    GroupedOpenApi publicApi(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("public")
                .displayName("Public & consumer")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch(concat(PUBLIC_READS, CUSTOMER))
                .pathsToExclude("/api/v1/me/businesses")
                .addOpenApiCustomizer(schemes(props))
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "Public & consumer", """
                            What the consumer web and the mobile apps call. Public reads (storefronts, catalogue, \
                            search) need no sign-in; the customer's own endpoints go through the consumer-bff \
                            (session cookie + CSRF header) or, from the mobile apps, with a DPoP-bound token.""");
                    api.setSecurity(customer());
                    publicReads(api);
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    @Bean
    GroupedOpenApi studioApi(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("studio")
                .displayName("Studio (businesses)")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch(STUDIO)
                .addOpenApiCustomizer(schemes(props))
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "Studio", """
                            The business Studio, through the studio-bff. Every `{merchantId}` endpoint checks that the \
                            caller is a member of that business with a role that grants the permission, on every \
                            request, and that the sign-in used a second factor (`acr=mfa`); otherwise 403 with `code` \
                            `not_a_member`, `insufficient_role` or `mfa_required`. Money-moving POSTs take an \
                            `Idempotency-Key` header (kept 24 h).""");
                    api.setSecurity(List.of(
                            ApiDocs.requirement(BFF_SESSION, CSRF), ApiDocs.requirement(OAUTH2, List.of("merchant"))));
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    @Bean
    GroupedOpenApi partnerApi(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("partner")
                .displayName("Partners")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch("/api/v1/merchants/**")
                .addOpenApiMethodFilter(method -> method.isAnnotationPresent(PartnerAccess.class))
                .addOperationCustomizer((operation, handler) -> {
                    var access = handler.getMethodAnnotation(PartnerAccess.class);
                    if (access != null) {
                        operation.setSecurity(List.of(ApiDocs.requirement(PARTNER, List.of(access.value()))));
                    }
                    return operation;
                })
                .addOpenApiCustomizer(schemes(props))
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "Partners", """
                            For partner integrations (S-30): a token from northline-auth with client credentials and \
                            `private_key_jwt`, carrying `api.read` / `api.write`. It opens only the businesses bound \
                            to the partner (otherwise 403 `not_bound`) and only these endpoints (otherwise 403 \
                            `partner_not_allowed`). Events arrive as signed webhooks (the `webhooks` document).""");
                    api.setSecurity(List.of(ApiDocs.requirement(PARTNER, List.of("api.read"))));
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    @Bean
    GroupedOpenApi consoleApi(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("console")
                .displayName("Console (staff)")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch(CONSOLE)
                .addOpenApiCustomizer(schemes(props))
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "Console", """
                            Platform staff: role `STAFF` and a second factor (`acr=mfa`) on every call (403 \
                            `mfa_required` otherwise).""");
                    api.setSecurity(List.of(
                            ApiDocs.requirement(BFF_SESSION, CSRF), ApiDocs.requirement(OAUTH2, List.of("openid"))));
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    @Bean
    GroupedOpenApi webhooksApi(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("webhooks")
                .displayName("Partner webhooks")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch("/none-webhooks-have-no-paths")
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "Webhooks", """
                            The events Northline POSTs to the endpoints a business subscribes in Studio › Settings › \
                            API (S-33). Payloads carry ids and amounts only, never customer data. Each body is \
                            validated against the schema shown here before it is sent.""");
                    WebhookDocs.addWebhooks(api, "classpath:spec/webhooks");
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    @Bean
    GroupedOpenApi internalApi(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("internal")
                .displayName("Internal (providers, links, dev)")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch(INTERNAL)
                .addOpenApiCustomizer(schemes(props))
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "Internal", """
                            Not for clients: callbacks from providers (Stripe, Google and Microsoft calendars, \
                            Shopify / Square / Lightspeed), each authenticated by the provider's signature or a \
                            single-use state instead of a token; signed email links; and the `local`-only dev tools. \
                            Never published outside local, dev and staging.""");
                    api.setSecurity(List.of());
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    /** The security schemes every api document may use. */
    private static OpenApiCustomizer schemes(DocsProperties props) {
        return api -> api.getComponents()
                .addSecuritySchemes(BFF_SESSION, ApiDocs.bffSession("NL_STUDIO"))
                .addSecuritySchemes(CSRF, ApiDocs.csrf())
                .addSecuritySchemes(OAUTH2, ApiDocs.oauth2(props.issuer()))
                .addSecuritySchemes(DPOP, ApiDocs.dpop())
                .addSecuritySchemes(DPOP_PROOF, ApiDocs.dpopProof())
                .addSecuritySchemes(PARTNER, ApiDocs.partnerClientCredentials(props.issuer()));
    }

    /** The consumer's ways in: the consumer-bff session, a BFF-relayed token, or the mobile apps' DPoP. */
    private static List<SecurityRequirement> customer() {
        return List.of(
                ApiDocs.requirement(BFF_SESSION, CSRF),
                ApiDocs.requirement(OAUTH2, List.of("openid")),
                ApiDocs.requirement(DPOP, DPOP_PROOF));
    }

    /** Public reads answer without a sign-in (an empty security requirement list). */
    private static void publicReads(OpenAPI api) {
        if (api.getPaths() == null) {
            return;
        }
        Consumer<io.swagger.v3.oas.models.Operation> open = operation -> operation.setSecurity(List.of());
        api.getPaths().forEach((path, item) -> {
            var isPublic =
                    List.of("/api/v1/public/", "/api/v1/storefronts/", "/api/v1/search/", "/api/v1/geo/").stream()
                            .anyMatch(path::startsWith);
            if (isPublic) {
                item.readOperations().forEach(open);
            }
        });
    }

    private static String[] concat(String[] a, String[] b) {
        var out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
