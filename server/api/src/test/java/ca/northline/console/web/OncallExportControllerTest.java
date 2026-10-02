package ca.northline.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.console.application.OncallExport;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** S-113: no export without a token (404); the calendar escapes text as RFC 5545 says. */
class OncallExportControllerTest {

    static OncallExport off() {
        return new OncallExport() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public boolean accepts(@Nullable String presented) {
                return false;
            }

            @Override
            public Export export() {
                throw new AssertionError("never read while off");
            }
        };
    }

    @Test
    void absentWhileNoTokenIsConfigured() {
        var controller = new OncallExportController(off());
        assertThat(controller.json("Bearer anything", null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(controller.calendar(null, "anything").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void escapesCalendarText() {
        var at = Instant.parse("2026-10-02T18:00:00Z");
        var ics = OncallExportController.ics(new OncallExport.Export(
                at,
                List.of(),
                List.of(new OncallExport.Entry(
                        "s1", "u1", "Ana; B", null, at, at.plusSeconds(3600), "Line1\nLine2, more"))));

        assertThat(ics)
                .contains("DTSTART:20261002T180000Z\r\n")
                .contains("DTEND:20261002T190000Z\r\n")
                .contains("SUMMARY:Ana\\; B\r\n") // no email: the name
                .contains("DESCRIPTION:Ana\\; B · Line1\\nLine2\\, more\r\n");
    }
}
