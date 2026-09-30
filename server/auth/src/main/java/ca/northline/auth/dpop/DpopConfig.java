package ca.northline.auth.dpop;

import ca.northline.auth.replay.ReplayStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * DPoP beans (S-29) over the {@link ReplayStore} (Valkey, or memory for local runs and tests): the nonces, the proof
 * verifier and the token-endpoint filter (after {@code TrustedProxyFilter}, so {@code htu} is compared with the public URL, and before
 * Spring Security).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DpopProperties.class)
class DpopConfig {

    @Bean
    DpopNonces dpopNonces(ReplayStore state, Clock clock, DpopProperties props) {
        return new DpopNonces(state, clock, props.nonceLifetime());
    }

    @Bean
    DpopProofs dpopProofs(ReplayStore state, DpopNonces nonces) {
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
