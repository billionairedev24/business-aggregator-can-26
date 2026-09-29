package ca.northline.config;

import ca.northline.shared.security.MerchantMemberships;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Registers {@link DevAuthFilter} inside the security chain — and only under the {@code local} profile. */
@Slf4j
@Profile("local")
@Configuration(proxyBeanMethods = false)
class DevAuthConfig {

    @Bean
    DevAuthFilter devAuthFilter(MerchantMemberships memberships, NorthlineJwtConverter converter, Clock clock) {
        log.warn("""

                ************************************************************************
                *  DEV AUTH ENABLED (profile 'local'): requests with header            *
                *  X-Dev-User: <identity.users id> are authenticated WITHOUT a token.  *
                *  Never activate the 'local' profile in any shared environment.       *
                ************************************************************************""");
        return new DevAuthFilter(memberships, converter, clock);
    }

    /** Keep the servlet container from also running it outside Spring Security's chain. */
    @Bean
    FilterRegistrationBean<DevAuthFilter> devAuthFilterRegistration(DevAuthFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
