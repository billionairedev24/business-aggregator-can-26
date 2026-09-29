package ca.northline.merchants.application;

import ca.northline.shared.CodedEnum;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port to Stripe Connect (Express accounts): account status and requirements, the Express dashboard login link
 * and hosted onboarding links. {@code local}/{@code test} use a fake that answers with the design's account; other
 * profiles call Stripe with {@code northline.stripe.secret-key}.
 */
public interface ConnectAccountGateway {

    /** Empty when Stripe doesn't know the account. */
    Optional<Account> account(String accountId);

    /** Creates an Express account for the business (CA, card payments + transfers); returns its id. */
    String createExpressAccount(String merchantId);

    /** Single-use login link to the Express dashboard. */
    String dashboardLink(String accountId);

    /** Hosted onboarding / update link ("Update identity document"). */
    String onboardingLink(String accountId, String returnUrl, String refreshUrl);

    record Account(
            String id,
            String type,
            boolean chargesEnabled,
            boolean payoutsEnabled,
            List<Requirement> requirements,
            @Nullable String bankLabel,
            @Nullable String statementDescriptor,
            @Nullable String payoutInterval,
            @Nullable String payoutWeekday,
            boolean instantPayouts) {

        public Account {
            requirements = List.copyOf(requirements);
        }
    }

    /** One line of "Stripe Connect account" (identity, business, bank, owners, annual re-verification). */
    record Requirement(Kind kind, State state, @Nullable Instant dueAt) {}

    enum Kind implements CodedEnum {
        IDENTITY,
        BUSINESS,
        BANK,
        OWNERS,
        ANNUAL_REVERIFICATION
    }

    enum State implements CodedEnum {
        VERIFIED,
        PENDING,
        DUE,
        PAST_DUE
    }
}
