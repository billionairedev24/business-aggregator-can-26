package ca.northline.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.PlaceNames;
import jakarta.servlet.Filter;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

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

    /** A filter that needs the database (dev auth, a membership lookup) and gets no connection within the timeout. */
    static final Filter NEEDS_THE_DATABASE = (_, _, _) -> {
        throw new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection",
                new SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 5000ms"));
    };

    /**
     * Engineering follow-ups (S-119 F7): the same timeout in a servlet filter escaped MVC's handler, and the container's
     * error dispatch answered 403 through Spring Security. Without {@link OverloadedFilter} the exception escapes.
     */
    @Test
    void noConnectionInAFilter_escapesWithoutTheOverloadedFilter() {
        var bare = MockMvcBuilders.standaloneSetup(new Busy())
                .addFilters(NEEDS_THE_DATABASE)
                .build();
        assertThatThrownBy(() -> bare.perform(get("/anything"))).isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    @Test
    void noConnectionInAFilter_is503Overloaded_withRetryAfter_inEnglishAndFrench() throws Exception {
        var guarded = MockMvcBuilders.standaloneSetup(new Busy())
                .addFilters(new OverloadedFilter(JsonMapper.builder().build()), NEEDS_THE_DATABASE)
                .build();
        guarded.perform(get("/anything"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.code").value("overloaded"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Northline is very busy right now. Try again in a moment."));
        guarded.perform(get("/anything").header(HttpHeaders.ACCEPT_LANGUAGE, "fr-CA"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.detail")
                        .value("Northline est très sollicité en ce moment. Réessayez dans un instant."));
    }

    @Test
    void otherFailuresInAFilterPassThrough_andTheFilterRunsBeforeSpringSecurity() {
        Filter broken = (_, _, _) -> {
            throw new IllegalStateException("a bug, not an overload");
        };
        var guarded = MockMvcBuilders.standaloneSetup(new Busy())
                .addFilters(new OverloadedFilter(JsonMapper.builder().build()), broken)
                .build();
        assertThatThrownBy(() -> guarded.perform(get("/anything"))).hasStackTraceContaining("a bug, not an overload");
        var order = AnnotationUtils.findAnnotation(OverloadedFilter.class, Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isLessThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }
}
