package ca.northline.console.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** S-113: the export is off without a token and compares the token exactly. */
class OncallExportServiceTest {

    OncallExportService service(String token) {
        return new OncallExportService(
                null, null, new OncallExportProperties(token, Duration.ofHours(12), Duration.ofDays(8)), null);
    }

    @Test
    void offWithoutAToken() {
        var off = service("");
        assertThat(off.enabled()).isFalse();
        assertThat(off.accepts("")).isFalse();
        assertThat(off.accepts(null)).isFalse();
    }

    @Test
    void acceptsOnlyTheConfiguredToken() {
        var on = service("fake-token-123");
        assertThat(on.enabled()).isTrue();
        assertThat(on.accepts("fake-token-123")).isTrue();
        assertThat(on.accepts("fake-token-12")).isFalse();
        assertThat(on.accepts("FAKE-TOKEN-123")).isFalse();
        assertThat(on.accepts(null)).isFalse();
    }
}
