package ca.northline.search.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** S-119: the load generators the search rate limit never counts ({@code SEARCH_RATE_LIMIT_EXEMPT}). */
class RateLimitExemptionsTest {

    private static MockEnvironment profiles(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void addressesAndRangesAreExempt_v4AndV6() {
        var exempt =
                RateLimitExemptions.of(List.of("127.0.0.1", " 10.20.0.0/16 ", "2001:db8::/32"), profiles("staging"));

        assertThat(exempt.covers("127.0.0.1")).isTrue();
        assertThat(exempt.covers("10.20.255.7")).isTrue();
        assertThat(exempt.covers("2001:db8::1")).isTrue();
        assertThat(exempt.covers("10.21.0.1")).isFalse();
        assertThat(exempt.covers("127.0.0.2")).isFalse();
        assertThat(exempt.covers("203.0.113.9")).isFalse();
    }

    @Test
    void nothingIsExemptByDefault_andBlankEntriesAreIgnored() {
        assertThat(RateLimitExemptions.of(List.of(), profiles("prod"))).isEqualTo(RateLimitExemptions.NONE);
        assertThat(RateLimitExemptions.of(List.of(" ", ""), profiles("prod"))).isEqualTo(RateLimitExemptions.NONE);
        assertThat(RateLimitExemptions.NONE.covers("127.0.0.1")).isFalse();
    }

    @Test
    void refusedUnderProd() {
        assertThatThrownBy(() -> RateLimitExemptions.of(List.of("10.0.0.0/8"), profiles("cloud", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused under prod");
    }

    @Test
    void aHostNameIsNeitherAcceptedNorLookedUp() {
        assertThatThrownBy(() -> RateLimitExemptions.of(List.of("loadgen.example.com"), profiles("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an IP address or CIDR range");
        assertThatThrownBy(() -> RateLimitExemptions.of(List.of("10.0.0.0/99"), profiles("local")))
                .isInstanceOf(IllegalStateException.class);
        var exempt = RateLimitExemptions.of(List.of("0.0.0.0/0"), profiles("local"));
        assertThat(exempt.covers("localhost")).isFalse();
        assertThat(exempt.covers("8.8.8.8")).isTrue();
    }
}
