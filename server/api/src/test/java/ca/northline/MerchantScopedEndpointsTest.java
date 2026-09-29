package ca.northline;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.security.RequiresMerchant;
import ca.northline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Every handler under {@code /api/v1/merchants/{merchantId}/…} must declare {@link RequiresMerchant}. */
class MerchantScopedEndpointsTest extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyMerchantScopedHandlerIsGuarded() {
        var unguarded = mappings.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().stream().anyMatch(p -> p.contains("{merchantId}")))
                .filter(e -> !AnnotatedElementUtils.hasAnnotation(e.getValue().getMethod(), RequiresMerchant.class)
                        && !AnnotatedElementUtils.hasAnnotation(e.getValue().getBeanType(), RequiresMerchant.class))
                .map(e -> e.getValue().toString())
                .toList();

        assertThat(unguarded)
                .as("handlers under {merchantId} without @RequiresMerchant")
                .isEmpty();
        assertThat(mappings.getHandlerMethods().keySet())
                .as("sanity: the reference slice is mapped")
                .anyMatch(info -> info.getPatternValues().contains("/api/v1/merchants/{merchantId}"));
    }
}
