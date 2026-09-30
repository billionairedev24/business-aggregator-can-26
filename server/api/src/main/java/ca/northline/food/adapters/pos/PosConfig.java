package ca.northline.food.adapters.pos;

import ca.northline.food.application.PosMenuSource;
import ca.northline.food.application.PosSettings;
import ca.northline.food.domain.PosProvider;
import ca.northline.shared.integration.Backoff;
import ca.northline.shared.integration.ProviderHttp;
import java.time.Clock;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The {@link PosMenuSource}s for {@code northline.pos.provider} ({@code POS_PROVIDER}): {@code local} = the fakes
 * (default; refused under {@code staging}/{@code prod}); {@code oauth} = Square, Clover and Toast, each offered once its
 * credentials are set.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PosProperties.class)
class PosConfig {

    @Bean
    PosSettings posSettings(PosProperties p) {
        return new PosSettings(p.apiUrl(), p.studioUrl());
    }

    private static Backoff backoff(PosProperties p, Clock clock) {
        return new Backoff(p.maxRetries(), p.maxBackoff(), Backoff.THREAD_SLEEP, clock);
    }

    @Bean
    PosMenuSource squarePosSource(PosProperties p, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakePosSource(PosProvider.SQUARE);
        }
        log.info("POS import: Square {}", p.square().configured() ? "configured" : "not configured (SQUARE_CLIENT_ID)");
        return new SquarePosSource(
                p.square(), ProviderHttp.client(SquarePosSource.Api.class), backoff(p, clock), clock);
    }

    @Bean
    PosMenuSource cloverPosSource(PosProperties p, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakePosSource(PosProvider.CLOVER);
        }
        log.info("POS import: Clover {}", p.clover().configured() ? "configured" : "not configured (CLOVER_CLIENT_ID)");
        return new CloverPosSource(
                p.clover(), ProviderHttp.client(CloverPosSource.Api.class), backoff(p, clock), clock);
    }

    @Bean
    PosMenuSource toastPosSource(PosProperties p, Clock clock, Environment env) {
        if (local(p, env)) {
            return new FakePosSource(PosProvider.TOAST);
        }
        log.info("POS import: Toast {}", p.toast().configured() ? "configured" : "not configured (TOAST_CLIENT_ID)");
        return new ToastPosSource(p.toast(), ProviderHttp.client(ToastPosSource.Api.class), backoff(p, clock), clock);
    }

    static boolean local(PosProperties p, Environment env) {
        return switch (p.effectiveProvider()) {
            case "local" -> {
                if (env.matchesProfiles("staging | prod")) {
                    throw new IllegalStateException("POS_PROVIDER=local is not allowed under staging/prod: set"
                            + " POS_PROVIDER=oauth (docs/runbooks/pos-menu-import.md)");
                }
                yield true;
            }
            case "oauth" -> false;
            default ->
                throw new IllegalStateException(
                        "POS_PROVIDER must be local or oauth, not " + Objects.requireNonNull(p.provider()));
        };
    }
}
