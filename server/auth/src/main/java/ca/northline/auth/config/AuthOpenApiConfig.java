package ca.northline.auth.config;

import static ca.northline.openapi.ApiDocs.AUTH_SESSION;
import static ca.northline.openapi.ApiDocs.DPOP_PROOF;
import static ca.northline.openapi.ApiDocs.OAUTH2;
import static ca.northline.openapi.ApiDocs.PARTNER;

import ca.northline.openapi.ApiDocs;
import ca.northline.openapi.DocsProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S-125: northline-auth's OpenAPI documents (docs/runbooks/api-docs.md).
 *
 * <ul>
 *   <li>{@code public} — the OAuth 2.1 / OpenID Connect endpoints every client uses (discovery, JWKS, authorize,
 *       token, revoke, userinfo). Spring Authorization Server implements them as filters, which springdoc can't see,
 *       so they are described here: authorization code + PKCE (BFFs, mobile apps with DPoP, S-29), refresh-token
 *       rotation, and client credentials with {@code private_key_jwt} for partners (S-30).
 *   <li>{@code internal} — the JSON sign-in API of the first-party apps ({@code /api/auth/**}: sign-in, registration,
 *       step-up, session, security). Cross-origin from the Studio and the consumer web, with credentials; unsafe
 *       methods need an allow-listed {@code Origin} (S-20).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "northline.docs", name = "enabled", matchIfMissing = true)
class AuthOpenApiConfig {

    @Bean
    GroupedOpenApi oauthEndpoints(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("public")
                .displayName("OAuth 2.1 / OpenID Connect")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch("/.well-known/**")
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "OAuth 2.1 / OpenID Connect", """
                            How every client gets tokens from northline-auth: the BFFs and the mobile apps with the \
                            authorization code flow and PKCE (S256 only), the mobile apps with DPoP-bound tokens \
                            (RFC 9449), partners with client credentials and `private_key_jwt` (RFC 7523). Access \
                            tokens are ES256 JWTs valid 10 minutes (partners 15); refresh tokens rotate on every \
                            use. Discovery: `/.well-known/openid-configuration`.""");
                    api.getComponents()
                            .addSecuritySchemes(OAUTH2, ApiDocs.oauth2(props.issuer()))
                            .addSecuritySchemes(PARTNER, ApiDocs.partnerClientCredentials(props.issuer()))
                            .addSecuritySchemes(DPOP_PROOF, ApiDocs.dpopProof())
                            .addSchemas("TokenResponse", tokenResponse())
                            .addSchemas("OAuthError", oauthError());
                    oauthPaths(api);
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    @Bean
    GroupedOpenApi signInApi(DocsProperties props) {
        return GroupedOpenApi.builder()
                .group("internal")
                .displayName("Sign-in JSON API (first-party apps)")
                .addOpenApiCustomizer(ApiDocs.base(props))
                .pathsToMatch("/api/auth/**")
                .addOpenApiCustomizer(api -> {
                    ApiDocs.describeGroup(api, "Sign-in JSON API", """
                            The JSON API behind the Studio's and the consumer web's sign-in, registration, step-up \
                            and Settings › Security pages. Not for third parties: they use the OAuth endpoints. \
                            Called cross-origin with credentials; POST and DELETE need an allow-listed `Origin` \
                            (403 otherwise), bodies are JSON only. Guessable secrets (codes, second factors) are rate \
                            limited per account, IP and session (429 with `retryAfterSeconds`); validation errors are \
                            422 in the shared format.""");
                    api.getComponents().addSecuritySchemes(AUTH_SESSION, authSession());
                    api.setSecurity(List.of(ApiDocs.requirement(AUTH_SESSION)));
                    if (api.getPaths() != null) {
                        api.getPaths().forEach((path, item) -> {
                            if (path.startsWith("/api/auth/sign-in") || path.startsWith("/api/auth/register")) {
                                item.readOperations().forEach(o -> o.setSecurity(List.of()));
                            }
                        });
                    }
                })
                .addOpenApiCustomizer(ApiDocs.conventions())
                .build();
    }

    private static SecurityScheme authSession() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("NL_AUTH")
                .description("northline-auth's own HttpOnly session cookie (`__Host-NL_AUTH` in the cloud), set when "
                        + "a sign-in or registration completes; 12 h idle timeout. Sign-in and registration "
                        + "endpoints work without it.");
    }

    // ---- the OAuth 2.1 / OIDC endpoints (filters in Spring Authorization Server) ---------------------------------

    private static void oauthPaths(OpenAPI api) {
        var paths = api.getPaths() == null ? new Paths() : api.getPaths();
        api.setPaths(paths);
        paths.addPathItem(
                "/.well-known/openid-configuration",
                new PathItem()
                        .get(new Operation()
                                .operationId("oidcDiscovery")
                                .summary("OpenID Provider metadata")
                                .description(
                                        "Issuer, endpoints, supported grant types, PKCE methods (S256), signing algorithms "
                                                + "(ES256), token endpoint authentication methods and DPoP algorithms.")
                                .security(List.of())
                                .responses(json("The provider metadata.", new ObjectSchema()))));
        paths.addPathItem(
                "/oauth2/jwks",
                new PathItem()
                        .get(new Operation()
                                .operationId("jwks")
                                .summary("Token signing keys (JWK Set)")
                                .description(
                                        "The public ES256 keys access and ID tokens are signed with. Cache it and refetch on an "
                                                + "unknown `kid`: keys rotate with an overlap (docs/runbooks/key-rotation.md).")
                                .security(List.of())
                                .responses(json("A JWK Set.", new ObjectSchema()))));
        paths.addPathItem(
                "/oauth2/authorize",
                new PathItem()
                        .get(new Operation()
                                .operationId("authorize")
                                .summary("Authorization endpoint (code + PKCE)")
                                .description(
                                        "Starts a sign-in in the browser. Redirect URIs must match a registered one exactly; "
                                                + "`code_challenge` with `S256` is required for every client. Business and staff clients get "
                                                + "`acr=mfa` only after a passkey or TOTP.")
                                .security(List.of())
                                .parameters(List.of(
                                        query("response_type", "Always `code`.", true, "code"),
                                        query("client_id", "The registered client.", true, "studio-bff"),
                                        query(
                                                "redirect_uri",
                                                "Exactly as registered.",
                                                true,
                                                "http://localhost:8082/login/oauth2/code/northline"),
                                        query("scope", "Space-separated scopes.", true, "openid profile merchant"),
                                        query(
                                                "state",
                                                "Opaque value echoed back (CSRF protection).",
                                                true,
                                                "af0ifjsldkj"),
                                        query(
                                                "code_challenge",
                                                "BASE64URL(SHA-256(code_verifier)).",
                                                true,
                                                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),
                                        query("code_challenge_method", "Only `S256`.", true, "S256"),
                                        query(
                                                "nonce",
                                                "OIDC replay protection for the ID token.",
                                                false,
                                                "n-0S6_WzA2Mj"),
                                        query(
                                                "dpop_jkt",
                                                "Mobile apps (S-29): thumbprint of the DPoP key the code is bound to.",
                                                false,
                                                "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs")))
                                .responses(new ApiResponses()
                                        .addApiResponse(
                                                "302",
                                                new ApiResponse()
                                                        .description(
                                                                "To the sign-in page, then back to `redirect_uri` with `code` and `state` (or "
                                                                        + "`error`).")))));
        paths.addPathItem(
                "/oauth2/token",
                new PathItem()
                        .post(new Operation()
                                .operationId("token")
                                .summary("Token endpoint")
                                .description("""
                        * `authorization_code` — `code`, `redirect_uri`, `code_verifier`; confidential clients (the \
                        BFFs) authenticate with their secret, public clients (the mobile apps) send `client_id` and a \
                        `DPoP` proof.
                        * `refresh_token` — rotates: the old refresh token stops working; a reused one revokes the \
                        whole sign-in.
                        * `client_credentials` — partners only: `client_assertion_type` = \
                        `urn:ietf:params:oauth:client-assertion-type:jwt-bearer` and a `client_assertion` signed by \
                        the partner's registered key (`private_key_jwt`); scopes `api.read` / `api.write`. 60 \
                        tokens per hour per partner (429 with `Retry-After`).""")
                                .security(List.of(
                                        ApiDocs.requirement(PARTNER, List.of("api.read")), ApiDocs.requirement(OAUTH2)))
                                .addParametersItem(new HeaderParameter()
                                        .name("DPoP")
                                        .required(false)
                                        .description(
                                                "DPoP proof JWT (mobile apps). The token is then of type `DPoP`, bound to it.")
                                        .schema(new StringSchema()))
                                .requestBody(new RequestBody()
                                        .required(true)
                                        .content(new Content()
                                                .addMediaType(
                                                        "application/x-www-form-urlencoded",
                                                        new MediaType().schema(tokenRequest()))))
                                .responses(json("Tokens.", ApiDocs.ref("TokenResponse"))
                                        .addApiResponse(
                                                "400",
                                                oauthErrorResponse(
                                                        "`invalid_grant`, `invalid_request`, "
                                                                + "`invalid_dpop_proof`, `use_dpop_nonce` (with a `DPoP-Nonce` header) …"))
                                        .addApiResponse(
                                                "401",
                                                oauthErrorResponse("`invalid_client`: unknown, revoked or "
                                                        + "unauthenticated client, or a refused assertion."))
                                        .addApiResponse(
                                                "429", oauthErrorResponse("`rate_limited`, with `Retry-After`.")))));
        paths.addPathItem(
                "/oauth2/revoke",
                new PathItem()
                        .post(new Operation()
                                .operationId("revoke")
                                .summary("Token revocation (RFC 7009)")
                                .description(
                                        "Revoking a refresh token ends that sign-in for every client sharing it (sign-out).")
                                .security(List.of(ApiDocs.requirement(OAUTH2)))
                                .requestBody(new RequestBody()
                                        .required(true)
                                        .content(new Content()
                                                .addMediaType(
                                                        "application/x-www-form-urlencoded",
                                                        new MediaType()
                                                                .schema(new ObjectSchema()
                                                                        .addProperty("token", new StringSchema())
                                                                        .addProperty(
                                                                                "token_type_hint",
                                                                                new StringSchema()
                                                                                        ._enum(List.of(
                                                                                                "refresh_token",
                                                                                                "access_token")))
                                                                        .required(List.of("token"))))))
                                .responses(new ApiResponses()
                                        .addApiResponse(
                                                "200",
                                                new ApiResponse()
                                                        .description("Revoked " + "(also for an unknown token).")))));
        paths.addPathItem(
                "/userinfo",
                new PathItem()
                        .get(new Operation()
                                .operationId("userinfo")
                                .summary("OIDC UserInfo")
                                .security(List.of(ApiDocs.requirement(OAUTH2, List.of("openid"))))
                                .responses(json(
                                        "Claims of the signed-in user (`sub`, `name`, `email`, `locale`, …).",
                                        new ObjectSchema()))));
    }

    private static Parameter query(String name, String description, boolean required, String example) {
        return new QueryParameter()
                .name(name)
                .description(description)
                .required(required)
                .schema(new StringSchema())
                .example(example);
    }

    private static ApiResponses json(String description, Schema<?> schema) {
        return new ApiResponses()
                .addApiResponse(
                        "200",
                        new ApiResponse()
                                .description(description)
                                .content(new Content()
                                        .addMediaType("application/json", new MediaType().schema(schema))));
    }

    private static ApiResponse oauthErrorResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType("application/json", new MediaType().schema(ApiDocs.ref("OAuthError"))));
    }

    private static Schema<?> tokenRequest() {
        return new ObjectSchema()
                .addProperty(
                        "grant_type",
                        new StringSchema()._enum(List.of("authorization_code", "refresh_token", "client_credentials")))
                .addProperty("code", new StringSchema())
                .addProperty("redirect_uri", new StringSchema())
                .addProperty("code_verifier", new StringSchema().description("PKCE verifier (43–128 characters)."))
                .addProperty("refresh_token", new StringSchema())
                .addProperty("client_id", new StringSchema().description("Required for public clients and partners."))
                .addProperty(
                        "client_assertion_type",
                        new StringSchema()._enum(List.of("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")))
                .addProperty("client_assertion", new StringSchema().description("Partners: the signed JWT."))
                .addProperty("scope", new StringSchema().example("api.read"))
                .required(List.of("grant_type"));
    }

    private static Schema<?> tokenResponse() {
        return new ObjectSchema()
                .addProperty("access_token", new StringSchema().description("ES256 JWT."))
                .addProperty("token_type", new StringSchema()._enum(List.of("Bearer", "DPoP")))
                .addProperty("expires_in", new IntegerSchema().example(600))
                .addProperty("refresh_token", new StringSchema().description("Not for client credentials."))
                .addProperty("id_token", new StringSchema().description("With the `openid` scope."))
                .addProperty("scope", new StringSchema())
                .required(List.of("access_token", "token_type", "expires_in"));
    }

    private static Schema<?> oauthError() {
        return new ObjectSchema()
                .description("RFC 6749 § 5.2 error.")
                .addProperty("error", new StringSchema().example("invalid_grant"))
                .addProperty("error_description", new StringSchema())
                .required(List.of("error"));
    }
}
