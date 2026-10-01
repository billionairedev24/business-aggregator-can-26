package ca.northline.studio;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.ai.api.AssistantTool;
import ca.northline.studio.application.AssistantEvalTools;
import ca.northline.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The assistant's tools in the running context: unique names, and the eval's stubs mirror them exactly. */
class AssistantToolsCatalogueTest extends IntegrationTest {

    @Autowired
    List<AssistantTool> tools;

    @Test
    void namesAreUniqueAndTheEvalMirrorsThem() {
        var names = tools.stream().map(AssistantTool::name).toList();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).allMatch(n -> n.matches("[a-z_]+"));
        var real = new java.util.HashMap<String, Object[]>();
        tools.forEach(t -> real.put(t.name(), new Object[] {t.permission(), t.write()}));
        assertThat(real.keySet()).containsExactlyInAnyOrderElementsOf(AssistantEvalTools.names());
        AssistantEvalTools.names().forEach(n -> {
            assertThat(real.get(n)[0]).as(n + " permission").isEqualTo(AssistantEvalTools.permission(n));
            assertThat(real.get(n)[1]).as(n + " write").isEqualTo(AssistantEvalTools.write(n));
        });
    }
}
