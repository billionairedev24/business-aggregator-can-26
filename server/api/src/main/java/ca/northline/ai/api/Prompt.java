package ca.northline.ai.api;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A versioned prompt file ({@code classpath:ai/prompts/<name>.v<version>.md}). Changing a prompt means adding the next
 * version; the id ({@code listing-copy@v2}) is recorded with every call's usage so answers can be traced to it.
 */
public record Prompt(String name, int version, String text) {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    public String id() {
        return name + "@v" + version;
    }

    /** The text with {@code {{var}}} replaced; an unknown variable is left visible (and caught by the prompt tests). */
    public String render(Map<String, ?> vars) {
        Matcher m = VAR.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            var value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
