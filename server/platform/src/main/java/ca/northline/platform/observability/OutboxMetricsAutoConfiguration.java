package ca.northline.platform.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S-113: {@link OutboxBacklog} in every app with a Modulith JDBC event registry ({@code spring.modulith.events.jdbc.schema}
 * set: the api and northline-auth). Spring Boot binds the {@code MeterBinder} to the app's meter registries.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
@ConditionalOnClass(
        name = {"org.springframework.jdbc.core.JdbcTemplate", "io.micrometer.core.instrument.binder.MeterBinder"})
@ConditionalOnProperty("spring.modulith.events.jdbc.schema")
public class OutboxMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    OutboxBacklog outboxBacklog(JdbcTemplate jdbc, @Value("${spring.modulith.events.jdbc.schema}") String schema) {
        return OutboxBacklog.of(jdbc, schema);
    }
}
