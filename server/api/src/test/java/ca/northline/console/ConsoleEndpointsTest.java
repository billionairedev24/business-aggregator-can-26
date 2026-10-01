package ca.northline.console;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.security.RequiresConsole;
import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** S-90: every handler under {@code /api/v1/console/} declares the screen it serves ({@link RequiresConsole}). */
class ConsoleEndpointsTest extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyConsoleHandlerIsGuardedByRole() {
        var console = mappings.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/v1/console/")))
                .toList();
        var unguarded = console.stream()
                .filter(e -> !AnnotatedElementUtils.hasAnnotation(e.getValue().getMethod(), RequiresConsole.class)
                        && !AnnotatedElementUtils.hasAnnotation(e.getValue().getBeanType(), RequiresConsole.class))
                .map(e -> e.getValue().toString())
                .toList();

        assertThat(unguarded).as("console handlers without @RequiresConsole").isEmpty();
        assertThat(console).as("sanity: the console shell's endpoint is mapped").isNotEmpty();
    }
}
