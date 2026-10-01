package ca.northline.ai.application;

import ca.northline.ai.api.Prompt;
import ca.northline.ai.api.Prompts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads {@code classpath:ai/prompts/<name>.v<N>.md} once at start-up and serves the highest {@code N} of each name.
 * Old versions stay in the repository, so an answer's recorded prompt id can always be read back.
 */
@Component
class PromptLibrary implements Prompts {

    private static final Pattern FILE = Pattern.compile("([a-z0-9-]+)\\.v(\\d+)\\.md");

    private final Map<String, Prompt> latest = new TreeMap<>();

    PromptLibrary() {
        try {
            for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:ai/prompts/*.md")) {
                var m = FILE.matcher(String.valueOf(resource.getFilename()));
                if (!m.matches()) {
                    throw new IllegalStateException("Prompt file names are <name>.v<N>.md: " + resource.getFilename());
                }
                var prompt = new Prompt(
                        m.group(1),
                        Integer.parseInt(m.group(2)),
                        resource.getContentAsString(StandardCharsets.UTF_8).strip());
                latest.merge(prompt.name(), prompt, (a, b) -> a.version() >= b.version() ? a : b);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Prompt get(String name) {
        var prompt = latest.get(name);
        if (prompt == null) {
            throw new IllegalArgumentException("No prompt called " + name + " under classpath:ai/prompts/");
        }
        return prompt;
    }

    Map<String, Prompt> all() {
        return Map.copyOf(latest);
    }
}
