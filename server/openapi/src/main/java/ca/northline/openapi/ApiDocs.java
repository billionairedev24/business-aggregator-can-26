package ca.northline.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Map;

/**
 * The building blocks every Northline spec shares: the error formats, ids and money conventions, and the security
 * schemes of the four ways a client authenticates (BFF session cookie + CSRF header, OAuth 2.1 authorization code with
 * PKCE, DPoP-bound tokens of the mobile apps, partner client credentials with {@code private_key_jwt}).
 */
public final class ApiDocs {

    public static final String PROBLEM = "Problem";
    public static final String VALIDATION_ERRORS = "ValidationErrors";
    public static final String VALIDATION_ERROR = "ValidationError";
    public static final String ULID = "Ulid";
    public static final String MONEY_CENTS = "MoneyCents";
    public static final String ULID_PATTERN = "^[0-9A-HJKMNP-TV-Z]{26}$";
    public static final String PROBLEM_JSON = "application/problem+json";

    /** Security scheme names. */
    public static final String BFF_SESSION = "bffSession";

    public static final String CSRF = "csrf";
    public static final String AUTH_SESSION = "authSession";
    public static final String OAUTH2 = "oauth2";
    public static final String DPOP = "dpop";
    public static final String DPOP_PROOF = "dpopProof";
    public static final String PARTNER = "partnerClientCredentials";

    /** Studio / console scopes of the first-party clients; partners get only {@code api.read} / {@code api.write}. */
    static final Map<String, String> FIRST_PARTY_SCOPES = Map.of(
            "openid", "OpenID Connect sign-in",
            "profile", "name, locale",
            "merchant", "the Studio: businesses the user is a member of (acr=mfa required)");

    private ApiDocs() {}

    /** Schemas shared by every spec: Problem, ValidationErrors, Ulid, MoneyCents. */
    static void addSharedSchemas(Components components) {
        components.addSchemas(
                ULID,
                new StringSchema()
                        .pattern(ULID_PATTERN)
                        .description("A ULID: 26 characters, Crockford base 32, sortable by creation time.")
                        .example("01J9ZD3V00000000000000PWM1"));
        components.addSchemas(
                MONEY_CENTS,
                new IntegerSchema()
                        .format("int64")
                        .description("An amount in cents of Canadian dollars (CAD); 12345 = $123.45. Never a decimal.")
                        .example(12345));
        components.addSchemas(
                PROBLEM,
                new ObjectSchema()
                        .description("RFC 9457 problem details. 403 and 409 carry a machine-readable `code`.")
                        .addProperty(
                                "type",
                                new StringSchema().format("uri").example("https://northline.ca/problems/forbidden"))
                        .addProperty("title", new StringSchema().example("Forbidden"))
                        .addProperty("status", new IntegerSchema().example(403))
                        .addProperty("detail", new StringSchema().example("Your sign-in doesn't allow this."))
                        .addProperty("instance", new StringSchema().format("uri-reference"))
                        .addProperty(
                                "code",
                                new StringSchema()
                                        .description(
                                                "Stable reason, e.g. `mfa_required`, `not_a_member`, `insufficient_role`, "
                                                        + "`partner_not_allowed`, `not_bound`, `stale`, `duplicate`, `rate_limited`.")
                                        .example("mfa_required"))
                        .required(List.of("type", "title", "status")));
        components.addSchemas(
                VALIDATION_ERROR,
                new ObjectSchema()
                        .addProperty(
                                "field",
                                new StringSchema()
                                        .description(
                                                "JSON property path of the request, e.g. `displayName` or `lines[2].amount`.")
                                        .example("displayName"))
                        .addProperty(
                                "rule",
                                new StringSchema()
                                        .description(
                                                "The failed rule: `required`, `format`, `length`, `range`, or a named rule "
                                                        + "(snake_case), e.g. `gst_number`.")
                                        .example("length"))
                        .addProperty(
                                "message",
                                new StringSchema()
                                        .description(
                                                "The exact message of docs/spec/validation-rules.md, in the caller's locale.")
                                        .example("At least 2 characters."))
                        .required(List.of("field", "rule", "message")));
        components.addSchemas(
                VALIDATION_ERRORS,
                new ObjectSchema()
                        .description("422: one error per field, the most basic failing rule first.")
                        .addProperty("errors", new ArraySchema().items(ref(VALIDATION_ERROR)))
                        .required(List.of("errors")));
    }

    /** The Northline conventions ({@link ApiConventions}); add it as each group's last customizer. */
    public static org.springdoc.core.customizers.OpenApiCustomizer conventions() {
        return new ApiConventions();
    }

    public static Schema<?> ref(String schema) {
        return new Schema<>().$ref("#/components/schemas/" + schema);
    }

    static ApiResponse problem(String description, String code) {
        var example = new Example()
                .value(Map.of(
                        "type", "https://northline.ca/problems/" + code.replace('_', '-'),
                        "title", description,
                        "status", status(code),
                        "detail", description,
                        "code", code));
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                PROBLEM_JSON,
                                new MediaType().schema(ref(PROBLEM)).addExamples(code, example)));
    }

    private static int status(String code) {
        return switch (code) {
            case "unauthorized" -> 401;
            case "not_found" -> 404;
            case "rate_limited" -> 429;
            default -> 403;
        };
    }

    static ApiResponse validationErrors() {
        var example = new Example()
                .value(Map.of(
                        "errors",
                        List.of(Map.of(
                                "field", "displayName", "rule", "length", "message", "At least 2 characters."))));
        return new ApiResponse()
                .description("Validation failed: one error per field with the exact message.")
                .content(new Content()
                        .addMediaType(
                                "application/json",
                                new MediaType().schema(ref(VALIDATION_ERRORS)).addExamples("length", example)));
    }

    // ---- security schemes -------------------------------------------------------------------------------------------

    /** The browser's session with a BFF: an HttpOnly cookie; the BFF relays the call with the user's access token. */
    public static SecurityScheme bffSession(String cookie) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(cookie)
                .description("Browsers only (the Studio, the consumer web): the BFF's HttpOnly session cookie "
                        + "(`__Host-` prefixed in the cloud). The BFF relays each call to the api with the user's "
                        + "access token; the browser never sees a token. Unsafe methods also need the `csrf` header.");
    }

    /** The CSRF double-submit header the BFF requires on unsafe methods (S-20: header only, never a form field). */
    public static SecurityScheme csrf() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-XSRF-TOKEN")
                .description("Copy of the `XSRF-TOKEN` cookie (`__Host-XSRF-TOKEN` in the cloud) on POST, PUT, "
                        + "PATCH and DELETE.");
    }

    /** OAuth 2.1 authorization code + PKCE (S256) at northline-auth: the BFFs and the mobile apps. */
    public static SecurityScheme oauth2(String issuer) {
        var scopes = new Scopes();
        FIRST_PARTY_SCOPES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> scopes.addString(e.getKey(), e.getValue()));
        return new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("OAuth 2.1 authorization code with PKCE (S256 only) at northline-auth; ES256 JWT access "
                        + "tokens valid 10 minutes with claims `sub`, `scope`, `roles`, `merchants`, `acr`. Business "
                        + "and staff calls need `acr=mfa` (passkey or TOTP). Confidential clients: the BFFs; public "
                        + "clients: the mobile apps (with DPoP).")
                .flows(new OAuthFlows()
                        .authorizationCode(new OAuthFlow()
                                .authorizationUrl(issuer + "/oauth2/authorize")
                                .tokenUrl(issuer + "/oauth2/token")
                                .refreshUrl(issuer + "/oauth2/token")
                                .scopes(scopes)));
    }

    /** RFC 9449 DPoP-bound access tokens of the mobile apps (S-29): {@code Authorization: DPoP <token>}. */
    public static SecurityScheme dpop() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("DPoP")
                .bearerFormat("JWT")
                .description("The mobile and courier apps (S-29): `Authorization: DPoP <access token>` together with "
                        + "the `DPoP` proof header. A DPoP-bound token (`cnf.jkt`) sent as a Bearer token is refused.");
    }

    /** The DPoP proof header (RFC 9449 § 4): a JWT signed by the app's key, bound to method, URL and token. */
    public static SecurityScheme dpopProof() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("DPoP")
                .description("DPoP proof JWT (`typ: dpop+jwt`, `htm`, `htu`, `iat`, `jti`, `ath`); a server nonce "
                        + "may be demanded with `DPoP-Nonce`.");
    }

    /** Partner clients (S-30): client credentials, authenticated with a signed JWT assertion (RFC 7523). */
    public static SecurityScheme partnerClientCredentials(String issuer) {
        var scheme = new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Partner integrations (S-30): client `partner:<name>` gets a token with "
                        + "`grant_type=client_credentials`, authenticating with `private_key_jwt` — "
                        + "`client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer` and a "
                        + "`client_assertion` JWT signed by a key registered for the partner (ES256, RS256 or PS256; "
                        + "`iss` = `sub` = client id, `aud` = issuer or token endpoint, ≤ 5 min, single-use `jti`). "
                        + "Tokens last 15 minutes and open only the businesses bound to the partner. 60 tokens per "
                        + "hour per partner (429 with Retry-After).")
                .flows(new OAuthFlows()
                        .clientCredentials(new OAuthFlow()
                                .tokenUrl(issuer + "/oauth2/token")
                                .scopes(new Scopes()
                                        .addString("api.read", "read the bound businesses' data")
                                        .addString("api.write", "change it"))));
        scheme.addExtension("x-token-endpoint-auth-methods", List.of("private_key_jwt"));
        return scheme;
    }

    public static SecurityRequirement requirement(String... schemes) {
        var requirement = new SecurityRequirement();
        for (var s : schemes) {
            requirement.addList(s);
        }
        return requirement;
    }

    public static SecurityRequirement requirement(String scheme, List<String> scopes) {
        return new SecurityRequirement().addList(scheme, scopes);
    }

    /** Title "Northline api · Studio" and a description line per group. */
    public static void describeGroup(OpenAPI openApi, String audience, String description) {
        var info = openApi.getInfo();
        info.setTitle(info.getTitle() + " · " + audience);
        info.setDescription(description
                + (info.getDescription() == null || info.getDescription().isBlank()
                        ? ""
                        : "\n\n" + info.getDescription()));
    }
}
