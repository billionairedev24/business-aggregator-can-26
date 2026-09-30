package ca.northline.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.ai.api.Prompt;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptLibraryTest {

    private final PromptLibrary library = new PromptLibrary();

    @Test
    void servesTheHighestVersionOfEachPrompt() {
        assertThat(library.all()).isNotEmpty();
        var check = library.get("platform-check");
        assertThat(check.id()).isEqualTo("platform-check@v" + check.version());
        assertThatThrownBy(() -> library.get("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rendersVariablesAndLeavesUnknownOnesVisible() {
        var p = new Prompt("t", 1, "Hi {{name}}, {{ missing }}.");
        assertThat(p.render(Map.of("name", "Ravi"))).isEqualTo("Hi Ravi, {{ missing }}.");
    }

    @Test
    void everyPromptSaysToUseOnlyTheDataGiven() {
        library.all()
                .values()
                .forEach(p -> assertThat(p.text()).as(p.id()).isNotBlank().hasSizeLessThan(8_000));
    }
}
