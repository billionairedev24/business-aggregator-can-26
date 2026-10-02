package ca.northline.worker.push;

import ca.northline.worker.notifications.PushSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Push wiring (S-102): {@code northline.push.provider} ({@code PUSH_PROVIDER}) = {@code local} keeps the
 * notifications' logging {@link PushSender}; {@code native} replaces it with {@link DevicePushSender} over APNs and
 * FCM, and fails at start-up naming every missing variable. Production refuses {@code local}. The registry is pruned
 * daily either way.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
public class PushConfiguration {

    static final String PROVIDER = "northline.push.provider";

    PushConfiguration(PushProperties props, Environment environment) {
        if (props.provider() == PushProperties.Provider.LOCAL && environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "PUSH_PROVIDER=local is not allowed under prod: set PUSH_PROVIDER=native and"
                            + " the APNs and FCM credentials (docs/runbooks/push.md)");
        }
        if (props.provider() == PushProperties.Provider.LOCAL && environment.matchesProfiles("dev | staging")) {
            log.warn("PUSH_PROVIDER=local: pushes are only logged (docs/runbooks/push.md)");
        }
    }

    @Bean
    PushDeviceStore pushDeviceStore(JdbcClient jdbc) {
        return new PushDeviceStore(jdbc);
    }

    @Bean
    PushPruneJob pushPruneJob(PushDeviceStore devices, PushProperties props, Clock clock) {
        return new PushPruneJob(devices, props, clock);
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "local", matchIfMissing = true)
    PushSender loggingPushSender() {
        return PushSender.LOGGING;
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "native")
    PushSender devicePushSender(
            PushDeviceStore devices, PushProperties props, JsonMapper json, Clock clock, MeterRegistry meters) {
        var problems = new ArrayList<String>();
        var apns = props.apns();
        var keyId = require(apns.keyId(), "PUSH_APNS_KEY_ID (the .p8 key's 10-character id)", problems);
        var teamId = require(apns.teamId(), "PUSH_APNS_TEAM_ID (the Apple developer team id)", problems);
        var key = require(apns.key(), "PUSH_APNS_KEY (the .p8 key, PEM)", problems);
        var account =
                require(props.fcm().serviceAccount(), "PUSH_FCM_SERVICE_ACCOUNT (service account JSON)", problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "PUSH_PROVIDER=native needs " + String.join(", ", problems) + " (docs/runbooks/push.md)");
        }
        var providers = List.<PushProvider>of(
                new ApnsPushProvider(
                        client(apns.url(), props, ApnsApi.class),
                        keyId,
                        teamId,
                        key,
                        apns.consumerTopic(),
                        apns.courierTopic(),
                        json,
                        clock),
                new FcmPushProvider(
                        client(props.fcm().url(), props, FcmApi.class),
                        account,
                        props.fcm().tokenUrl(),
                        json,
                        clock));
        log.info(
                "Push: provider=native APNs {} (key {}, topics {} / {}), FCM {}",
                apns.url(),
                keyId,
                apns.consumerTopic(),
                apns.courierTopic(),
                props.fcm().url());
        return new DevicePushSender(devices, providers, props, clock, meters);
    }

    /** HTTP/2 (APNs requires it; FCM accepts it), short time-outs, no redirects. */
    static <T> T client(String baseUrl, PushProperties props, Class<T> api) {
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(props.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(props.timeout());
        var rest = RestClient.builder()
                .baseUrl(baseUrl.strip())
                .requestFactory(requests)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest))
                .build()
                .createClient(api);
    }

    private static String require(@org.jspecify.annotations.Nullable String value, String what, List<String> problems) {
        if (!StringUtils.hasText(value)) {
            problems.add(what);
            return "";
        }
        return value.strip();
    }

    /** Daily: installations that haven't refreshed within {@code northline.push.stale-after} leave the registry. */
    static class PushPruneJob {
        private final PushDeviceStore devices;
        private final PushProperties props;
        private final Clock clock;

        PushPruneJob(PushDeviceStore devices, PushProperties props, Clock clock) {
            this.devices = devices;
            this.props = props;
            this.clock = clock;
        }

        @Scheduled(
                fixedDelayString = "${northline.push.prune-every:24h}",
                initialDelayString = "${northline.push.prune-initial-delay:10m}")
        void run() {
            var pruned = devices.prune(clock.instant().minus(props.staleAfter()));
            if (pruned > 0) {
                log.info("Push registry: {} installations not seen for {} removed", pruned, props.staleAfter());
            }
        }
    }
}
