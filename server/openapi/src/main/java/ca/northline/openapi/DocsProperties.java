package ca.northline.openapi;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.docs.*}: the documentation an app serves (S-125).
 *
 * @param enabled the viewers and the specs; {@code false} in production, where the published specs come from the
 *     docs site (S-126) and internal ones are never exposed
 * @param basePath prefix of the viewer pages, e.g. {@code /bff} for the BFF, whose public routes all live under /bff
 * @param title document title (each group adds its audience)
 * @param description document description (Markdown)
 * @param issuer northline-auth's issuer, for the OAuth 2.1 flows in the security schemes
 * @param servers the {@code servers} list; fixed (never the request's host) so the committed specs are reproducible
 */
@ConfigurationProperties("northline.docs")
public record DocsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String basePath,
        @DefaultValue("Northline") String title,
        @DefaultValue("") String description,
        @DefaultValue("http://localhost:9000") String issuer,
        @DefaultValue("http://localhost:8080") List<String> servers) {

    /** {@code basePath + path}, e.g. {@code /bff/docs/redoc}. */
    public String path(String path) {
        return basePath + path;
    }
}
