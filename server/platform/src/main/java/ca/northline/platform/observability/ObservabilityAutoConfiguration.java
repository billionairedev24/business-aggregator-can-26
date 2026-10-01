package ca.northline.platform.observability;

import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * S-111 wiring every app shares. Runs before Spring Boot's OpenTelemetry tracing auto-configuration so its
 * {@link Sampler} replaces the default one.
 */
@AutoConfiguration(
        beforeName =
                "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration")
public class ObservabilityAutoConfiguration {

    /** {@link ConsistentSampling}: callers can't force sampling; every service decides the same way. */
    @Bean
    @ConditionalOnClass(Sampler.class)
    @ConditionalOnMissingBean
    Sampler otelSampler(@Value("${management.tracing.sampling.probability:1.0}") double probability) {
        return ConsistentSampling.sampler(probability);
    }

    /**
     * Carries the trace (and every other Micrometer context) onto {@code @Async} work — Spring Modulith's
     * {@code @ApplicationModuleListener}s and the event externalizer run there — so a Kafka record keeps the trace of
     * the request that published it. Spring Boot applies a {@link TaskDecorator} bean to its task executor.
     */
    @Bean
    @ConditionalOnClass(name = "io.micrometer.context.ContextSnapshotFactory")
    @ConditionalOnMissingBean(TaskDecorator.class)
    TaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ObservationPredicate.class, ServerRequestObservationContext.class})
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class Probes {

        /** Kubernetes probes and metric scrapes every few seconds are not traffic: no spans, no request metrics. */
        @Bean
        ObservationPredicate noActuatorObservations() {
            return (_, context) -> !(context instanceof ServerRequestObservationContext request && probe(request));
        }

        private static boolean probe(ServerRequestObservationContext request) {
            HttpServletRequest http = request.getCarrier();
            return http != null && http.getRequestURI().startsWith("/actuator");
        }
    }
}
