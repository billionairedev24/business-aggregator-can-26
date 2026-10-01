package ca.northline.openapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <base>/docs}: a landing page listing every group with its three viewers (Swagger UI and Scalar from springdoc's
 * starters — Scalar shows every group on one page, with a document switcher —, Redoc from here) and the raw specs;
 * {@code <base>/docs/redoc}: Redoc with a group picker. Redoc's bundle is served from the classpath (fetched from the
 * npm registry at build time, see openapi/build.gradle.kts); no page loads anything from another origin, and no page
 * has an inline script (the CSP of {@link DocsSecurityConfiguration}). Functional routes rather than a
 * {@code @Controller}: the api scans {@code ca.northline.**}, which would register an annotated controller twice.
 */
final class DocsPagesController {

    private static final CacheControl ASSETS =
            CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
    private static final MediaType JS = MediaType.parseMediaType("text/javascript;charset=UTF-8");
    private static final MediaType CSS = MediaType.parseMediaType("text/css;charset=UTF-8");

    private final DocsProperties props;
    private final ObjectProvider<List<GroupedOpenApi>> groups;
    private final Environment environment;

    DocsPagesController(DocsProperties props, ObjectProvider<List<GroupedOpenApi>> groups, Environment environment) {
        this.props = props;
        this.groups = groups;
        this.environment = environment;
    }

    RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .GET(props.path("/docs"), _ -> html(landing()))
                .GET(props.path("/docs/redoc"), _ -> html(redoc()))
                .GET(props.path("/docs/redoc/redoc.standalone.js"), _ -> asset("redoc/redoc.standalone.js", JS))
                .GET(props.path("/docs/assets/redoc-init.js"), _ -> asset("redoc-init.js", JS))
                .GET(props.path("/docs/assets/docs.css"), _ -> asset("docs.css", CSS))
                .build();
    }

    String landing() {
        var rows = new StringBuilder();
        for (var group : groups()) {
            var name = group.getGroup();
            rows.append("<tr><th scope=\"row\">")
                    .append(esc(label(group)))
                    .append("<br><code>")
                    .append(esc(name))
                    .append("</code></th><td>")
                    .append(link(swaggerUi() + "?urls.primaryName=" + name, "Swagger UI"))
                    .append(link(scalar(), "Scalar"))
                    .append(link(props.path("/docs/redoc") + "?group=" + name, "Redoc"))
                    .append("</td><td>")
                    .append(link(apiDocs() + "/" + name, "JSON"))
                    .append(link(apiDocs() + ".yaml/" + name, "YAML"))
                    .append("</td></tr>\n");
        }
        return template("landing.html")
                .replace("{{title}}", esc(props.title()))
                .replace("{{base}}", esc(props.basePath()))
                .replace("{{rows}}", rows.toString());
    }

    String redoc() {
        var options = new StringBuilder();
        for (var group : groups()) {
            options.append("<option value=\"")
                    .append(esc(apiDocs() + "/" + group.getGroup()))
                    .append("\" data-group=\"")
                    .append(esc(group.getGroup()))
                    .append("\">")
                    .append(esc(label(group)))
                    .append("</option>");
        }
        return template("redoc.html")
                .replace("{{title}}", esc(props.title()))
                .replace("{{base}}", esc(props.basePath()))
                .replace("{{options}}", options.toString());
    }

    private List<GroupedOpenApi> groups() {
        return groups.getIfAvailable(List::of);
    }

    private static String label(GroupedOpenApi group) {
        var display = group.getDisplayName();
        return display == null || display.isBlank() ? group.getGroup() : display;
    }

    private String apiDocs() {
        return environment.getProperty("springdoc.api-docs.path", "/v3/api-docs");
    }

    private String swaggerUi() {
        return environment.getProperty("springdoc.swagger-ui.path", "/swagger-ui.html");
    }

    private String scalar() {
        return environment.getProperty("scalar.path", "/scalar");
    }

    private static ServerResponse html(String body) {
        return ServerResponse.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(body);
    }

    private static ServerResponse asset(String name, MediaType type) throws IOException {
        var resource = new ClassPathResource("northline-docs/" + name);
        if (!resource.exists()) {
            return ServerResponse.notFound().build();
        }
        return ServerResponse.ok().cacheControl(ASSETS).contentType(type).body(resource.getContentAsByteArray());
    }

    private static String link(String href, String text) {
        return "<a href=\"" + esc(href) + "\">" + text + "</a> ";
    }

    private static String esc(String text) {
        return HtmlUtils.htmlEscape(text);
    }

    private static String template(String name) {
        try {
            return new ClassPathResource("northline-docs/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
