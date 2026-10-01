package ca.northline.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * S-112: under a deployed profile every app logs one ECS JSON object per line, with every string redacted; locally
 * the plain text stays (a developer reads it); {@code LOG_FORMAT} overrides both.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogsTest {

    @Configuration(proxyBeanMethods = false)
    static class Empty {}

    @Test
    void deployedProfilesLogRedactedEcsJson(CapturedOutput output) {
        try (var _ = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .profiles("dev")
                .properties("spring.application.name=northline-test", "spring.main.banner-mode=off")
                .run()) {
            MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            MDC.put("password", "hunter2");
            try {
                LoggerFactory.getLogger("ca.northline.test")
                        .warn(
                                "Invitation for amara.osei@example.ca, +1 587 555 0101, card 4242 4242 4242 4242, T2P 1B5",
                                new IllegalStateException("token=abcd1234efgh for ravi@example.ca"));
            } finally {
                MDC.clear();
            }
        }
        var line = output.getOut()
                .lines()
                .filter(l -> l.contains("\"log.logger\":\"ca.northline.test\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JSON line in:\n" + output.getOut()));
        assertThat(line).startsWith("{").contains("\"ecs\":{\"version\"");
        assertThat(line).contains("\"message\":\"Invitation for [EMAIL], [PHONE], card [CARD …4242], T2P ***\"");
        assertThat(line).contains("\"trace.id\":\"4bf92f3577b34da6a3ce929d0e0e4736\"");
        assertThat(line).contains("\"password\":\"[REDACTED]\"");
        assertThat(line).contains("token=[REDACTED] for [EMAIL]");
        assertThat(line).contains("\"service\":{\"name\":\"northline-test\"").contains("\"environment\":\"dev\"");
        assertThat(line)
                .doesNotContain("amara.osei", "555 0101", "4242 4242", "1B5", "hunter2", "abcd1234", "ravi@");
    }

    @Test
    void localKeepsPlainTextUnlessLogFormatSaysOtherwise() {
        var local = environment("local", Map.of());
        assertThat(local.getProperty("logging.structured.format.console")).isNull();
        assertThat(local.getProperty("logging.structured.json.customizer"))
                .isEqualTo(RedactingJsonMembersCustomizer.class.getName());

        var localJson = environment("local", Map.of("LOG_FORMAT", "logstash"));
        assertThat(localJson.getProperty("logging.structured.format.console")).isEqualTo("logstash");

        var prodText = environment("prod", Map.of("LOG_FORMAT", "text"));
        assertThat(prodText.getProperty("logging.structured.format.console")).isNull();
        assertThat(environment("prod", Map.of()).getProperty("logging.structured.format.console"))
                .isEqualTo("ecs");
    }

    private static StandardEnvironment environment(String profile, Map<String, Object> env) {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        new LoggingDefaults().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
