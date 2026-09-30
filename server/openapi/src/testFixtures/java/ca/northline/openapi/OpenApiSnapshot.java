package ca.northline.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S-125 drift check: the specs the running code serves ({@code <api-docs>.yaml/<group>}) must equal the committed
 * {@code docs/api/openapi/<service>-<group>.yaml}. With {@code -Dopenapi.write=true} ({@code make openapi}) it writes the
 * files instead. Runs in each app's {@code OpenApiSpecsTest}, so {@code ./gradlew build} fails on a difference.
 */
public final class OpenApiSnapshot {

    private OpenApiSnapshot() {}

    /** The repository's docs/api/openapi (system property {@code northline.repo}, set by the build). */
    public static Path directory() {
        return Path.of(System.getProperty("northline.repo", "../..")).resolve("docs/api/openapi");
    }

    public static boolean writing() {
        return Boolean.parseBoolean(System.getProperty("openapi.write", "false"));
    }

    /** Fetches each group's YAML and compares it with (or writes) {@code <service>-<group>.yaml}. */
    public static void verify(MockMvc mvc, String apiDocsPath, String service, List<String> groups) throws Exception {
        var stale = new ArrayList<String>();
        for (var group : groups) {
            var response =
                    mvc.perform(get(apiDocsPath + ".yaml/" + group)).andReturn().getResponse();
            assertThat(response.getStatus()).as(apiDocsPath + ".yaml/" + group).isEqualTo(200);
            var yaml = normalise(response.getContentAsString(StandardCharsets.UTF_8));
            var file = directory().resolve(service + "-" + group + ".yaml");
            if (writing()) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, yaml);
            } else if (!Files.isRegularFile(file) || !Files.readString(file).equals(yaml)) {
                stale.add(file.getFileName().toString());
            }
        }
        assertThat(stale)
                .as("The code's OpenAPI differs from the committed docs/api/openapi files. Regenerate them with "
                        + "`make openapi`, review the diff and commit it.")
                .isEmpty();
    }

    private static String normalise(String yaml) {
        var text = yaml.replace("\r\n", "\n");
        return text.endsWith("\n") ? text : text + "\n";
    }
}
