package ca.northline.merchants.integration;

import ca.northline.merchants.application.ConnectAccountGateway;
import ca.northline.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LOCAL / TEST ONLY. Stripe Connect stand-in answering with the design's account (design 02 {@code stripeReqs}): an
 * Express account with charges and payouts enabled, every requirement verified except the annual ID re-verification
 * due in 12 days, TD ··3391, weekly Friday payouts. Account ids containing {@code "missing"} are unknown to it.
 */
@Slf4j
@Component
@Profile({"local", "test"})
@RequiredArgsConstructor
class FakeConnectAccountGateway implements ConnectAccountGateway {

    private final Clock clock;

    @Override
    public Optional<Account> account(String accountId) {
        if (accountId.contains("missing")) {
            return Optional.empty();
        }
        var reverify = clock.instant().plus(Duration.ofDays(12));
        return Optional.of(new Account(
                accountId,
                "express",
                true,
                true,
                List.of(
                        new Requirement(Kind.IDENTITY, State.VERIFIED, null),
                        new Requirement(Kind.BUSINESS, State.VERIFIED, null),
                        new Requirement(Kind.BANK, State.VERIFIED, null),
                        new Requirement(Kind.OWNERS, State.VERIFIED, null),
                        new Requirement(Kind.ANNUAL_REVERIFICATION, State.DUE, reverify)),
                "TD ··3391",
                null,
                "weekly",
                "friday",
                true));
    }

    @Override
    public String createExpressAccount(String merchantId) {
        var id = "acct_fake" + Ids.next().substring(10);
        log.info("FAKE Stripe: created Express account {} for {}", id, merchantId);
        return id;
    }

    @Override
    public String dashboardLink(String accountId) {
        return "https://connect.stripe.com/express/fake-login/" + accountId;
    }

    @Override
    public String onboardingLink(String accountId, String returnUrl, String refreshUrl) {
        return returnUrl + "?stripe=fake-onboarding&account=" + accountId;
    }
}
