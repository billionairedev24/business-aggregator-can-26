package ca.northline.config;

import ca.northline.shared.DomainEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;

/**
 * Modulith's default externalization ({@code @Externalized("<topic>::<key>")} on the event) plus the envelope headers
 * the worker's consumers need to pick the schema and dedupe ({@link EventHeaders}).
 */
@Configuration(proxyBeanMethods = false)
class EventExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.defaults("ca.northline")
                .headers(DomainEvent.class, EventHeaders::of)
                .build();
    }
}
