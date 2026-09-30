package ca.northline.auth.partners;

import ca.northline.auth.replay.ReplayStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** The partner assertion check (S-30); partners' JWK Sets are fetched with 5 s time-outs. */
@Configuration(proxyBeanMethods = false)
class PartnersConfig {

    @Bean
    PartnerAssertions partnerAssertions(ReplayStore replay, Clock clock) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(5));
        return new PartnerAssertions(replay, clock, new RestTemplate(requests));
    }
}
