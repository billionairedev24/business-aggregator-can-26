package ca.northline.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredEnvironmentCheckTest {

    @Test
    void passesWhenNothingIsRequired() {
        assertThatNoException().isThrownBy(() -> RequiredEnvironmentCheck.check(new MockEnvironment()));
    }

    @Test
    void passesWhenEveryVariableIsSet() {
        var env = new MockEnvironment()
                .withProperty("northline.required-env.database", "DB_URL, DB_USER")
                .withProperty("DB_URL", "jdbc:postgresql://db/northline")
                .withProperty("DB_USER", "northline");
        assertThatNoException().isThrownBy(() -> RequiredEnvironmentCheck.check(env));
    }

    @Test
    void reportsEveryMissingOrBlankVariableGroupedByPurpose() {
        var env = new MockEnvironment()
                .withProperty("northline.required-env.database", "DB_URL, DB_USER, DB_PASSWORD")
                .withProperty("northline.required-env.cache[0]", "REDIS_HOST")
                .withProperty("DB_URL", "jdbc:postgresql://db/northline")
                .withProperty("DB_USER", "  ");
        env.setActiveProfiles("dev", "cloud");

        assertThatThrownBy(() -> RequiredEnvironmentCheck.check(env))
                .isInstanceOfSatisfying(MissingEnvironmentException.class, e -> {
                    assertThat(e.profiles()).containsExactly("dev", "cloud");
                    assertThat(e.missing())
                            .isEqualTo(Map.of(
                                    "cache", List.of("REDIS_HOST"),
                                    "database", List.of("DB_USER", "DB_PASSWORD")));
                })
                .hasMessageContaining("cache: REDIS_HOST, database: DB_USER, DB_PASSWORD");
    }

    @Test
    void failureReportNamesTheRunbook() {
        var analysis = new MissingEnvironmentFailureAnalyzer()
                .analyze(new MissingEnvironmentException(
                        List.of("staging", "cloud"), Map.of("database", List.of("DB_URL"))));
        assertThat(analysis.getDescription()).contains("database: DB_URL");
        assertThat(analysis.getAction()).contains("docs/runbooks/staging.md");
    }
}
