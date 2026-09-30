package ca.northline.auth.dpop;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * DPoP beans (S-29): the state store chosen by {@code northline.auth.dpop.store} ({@code redis} — the default and the
 * only choice under staging/prod — or {@code memory} for local runs and tests), the nonces, the proof verifier and the
 * token-endpoint filter (after {@code TrustedProxyFilter}, so {@code htu} is compared with the public URL, and before
 * Spring Security).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DpopProperties.class)
class DpopConfig {

    @Bean
    DpopState dpopState(
            DpopProperties props, ObjectProvider<RedisConnectionFactory> redis, Clock clock, Environment environment) {
        return switch (props.store()) {
            case REDIS -> {
                log.info("DPoP proof ids and nonces (S-29) in Valkey/Redis; unreachable = token requests with DPoP"
                        + " answer 503");
                yield new RedisDpopState(new StringRedisTemplate(redis.getObject()));
            }
            case MEMORY -> {
                if (environment.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("northline.auth.dpop.store=memory is not allowed under"
                            + " staging/prod: every instance must see every used DPoP proof (Valkey)");
                }
                log.warn("DPoP proof ids and nonces (S-29) are kept IN MEMORY: per instance — local development and"
                        + " tests only. Use the `valkey` profile (or northline.auth.dpop.store=redis) to share them.");
                yield new InMemoryDpopState(clock);
            }
        };
    }

    @Bean
    DpopNonces dpopNonces(DpopState state, Clock clock, DpopProperties props) {
        return new DpopNonces(state, clock, props.nonceLifetime());
    }

    @Bean
    DpopProofs dpopProofs(DpopState state, DpopNonces nonces) {
        return new DpopProofs(state, nonces);
    }

    @Bean
    FilterRegistrationBean<DpopTokenEndpointFilter> dpopTokenEndpointFilter(
            DpopProofs proofs, DpopNonces nonces, RegisteredClientRepository clients) {
        var registration = new FilterRegistrationBean<>(new DpopTokenEndpointFilter(proofs, nonces, clients));
        registration.addUrlPatterns("/oauth2/token");
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 2);
        return registration;
    }
}
