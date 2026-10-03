package ca.northline.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.PlaceNames;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-119: when no database connection frees up within DB_CONNECTION_TIMEOUT_MS the request is shed — 503 {@code
 * overloaded} with {@code Retry-After}, in the caller's language — instead of a 500 (and instead of queueing until the
 * heap runs out).
 */
class OverloadedTest {

    @RestController
    static class Busy {
        @GetMapping("/busy")
        String busy() {
            throw new CannotGetJdbcConnectionException(
                    "Failed to obtain JDBC Connection",
                    new SQLTransientConnectionException(
                            "HikariPool-1 - Connection is not available, request timed out after 5000ms"));
        }
    }

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Busy())
            .setControllerAdvice(
                    new ApiExceptionHandler(new StaticListableBeanFactory().getBeanProvider(PlaceNames.class)))
            .build();

    @Test
    void noConnectionWithinTheTimeout_is503Overloaded_withRetryAfter_inEnglishAndFrench() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.code").value("overloaded"))
                .andExpect(jsonPath("$.detail").value("Northline is very busy right now. Try again in a moment."));
        mvc.perform(get("/busy").header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CA"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail")
                        .value("Northline est très sollicité en ce moment. Réessayez dans un instant."));
    }
}
