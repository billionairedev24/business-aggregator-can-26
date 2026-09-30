package ca.northline.openapi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <base>/docs}: a landing page listing every group with its three viewers (Swagger UI and Scalar from springdoc's
 * starters, Redoc from here) and the raw specs; {@code <base>/docs/redoc}: Redoc with a group picker. Redoc's bundle is
 * served from the classpath (fetched from the npm registry at build time, see openapi/build.gradle.kts); no page loads
 * anything from another origin, and no page has an inline script (the CSP of {@link DocsSecurityConfiguration}).
 */
@ResponseBody
final class DocsPagesController {

    private static final CacheControl ASSETS =
            CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final DocsProperties props;
    private final ObjectProvider<List<GroupedOpenApi>> groups;
    private final Environment environment;

    DocsPagesController(DocsProperties props, ObjectProvider<List<GroupedOpenApi>> groups, Environment environment) {
        this.props = props;
        this.groups = groups;
        this.environment = environment;
    }

    @GetMapping(path = "${northline.docs.base-path:}/docs", produces = MediaType.TEXT_HTML_VALUE)
    String landing() throws IOException {
        var rows = new StringBuilder();
        for (var group : groups()) {
            var name = group.getGroup();
            var label = group.getDisplayName() == null ? name : group.getDisplayName();
            rows.append("<tr><th scope=\"row\">")
                    .append(esc(label))
                    .append("<br><code>")
                    .append(esc(name))
                    .append("</code></th><td>")
                    .append(link(swaggerUi() + "?urls.primaryName=" + name, "Swagger UI"))
                    .append(link(scalar() + "/" + name, "Scalar"))
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

    @GetMapping(path = "${northline.docs.base-path:}/docs/redoc", produces = MediaType.TEXT_HTML_VALUE)
    String redoc() throws IOException {
        var options = new StringBuilder();
        for (var group : groups()) {
            var label = group.getDisplayName() == null ? group.getGroup() : group.getDisplayName();
            options.append("<option value=\"")
                    .append(esc(apiDocs() + "/" + group.getGroup()))
                    .append("\" data-group=\"")
                    .append(esc(group.getGroup()))
                    .append("\">")
                    .append(esc(label))
                    .append("</option>");
        }
        return template("redoc.html")
                .replace("{{title}}", esc(props.title()))
                .replace("{{base}}", esc(props.basePath()))
                .replace("{{options}}", options.toString());
    }

    @GetMapping(path = "${northline.docs.base-path:}/docs/redoc/redoc.standalone.js", produces = "text/javascript")
    ResponseEntity<byte[]> redocBundle() throws IOException {
        return asset("redoc/redoc.standalone.js", "text/javascript");
    }

    @GetMapping(path = "${northline.docs.base-path:}/docs/assets/redoc-init.js", produces = "text/javascript")
    ResponseEntity<byte[]> redocInit() throws IOException {
        return asset("redoc-init.js", "text/javascript");
    }

    @GetMapping(path = "${northline.docs.base-path:}/docs/assets/docs.css", produces = "text/css")
    ResponseEntity<byte[]> css() throws IOException {
        return asset("docs.css", "text/css");
    }

    private List<GroupedOpenApi> groups() {
        return groups.getIfAvailable(List::of);
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

    private static String link(String href, String text) {
        return "<a href=\"" + esc(href) + "\">" + text + "</a> ";
    }

    private static String esc(String text) {
        return HtmlUtils.htmlEscape(text);
    }

    private static String template(String name) throws IOException {
        return new ClassPathResource("northline-docs/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private static ResponseEntity<byte[]> asset(String name, String type) throws IOException {
        var resource = new ClassPathResource("northline-docs/" + name);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(ASSETS)
                .contentType(MediaType.parseMediaType(type + ";charset=UTF-8"))
                .body(resource.getContentAsByteArray());
    }
}
