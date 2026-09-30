package ca.northline.auth.config;

import ca.northline.platform.EventHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;

/**
 * The api's externalization, shared through {@link EventHeaders}: {@code @Externalized("<topic>::<key>")} on the event
 * plus the {@code nl-event-id|type|version} headers the worker's consumers require (S-26) — without them the worker
 * dead-letters {@code user.registered} as poison.
 */
@Configuration(proxyBeanMethods = false)
class EventExternalizationConfig {

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventHeaders.externalization("ca.northline.auth");
    }
}
