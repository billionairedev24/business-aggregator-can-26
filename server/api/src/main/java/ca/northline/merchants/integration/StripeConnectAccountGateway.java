package ca.northline.merchants.integration;

import ca.northline.merchants.application.ConnectAccountGateway;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.stripe.StripeClients;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account.Requirements;
import com.stripe.model.BankAccount;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.AccountLoginLinkCreateParams;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe Connect Express through stripe-java ({@code northline.stripe.secret-key}, API version pinned by
 * {@link StripeClients}). Accounts are created for Canada with the {@code card_payments} and {@code transfers}
 * capabilities, CAD, Stripe's automatic payouts off (Northline's payout run decides — see payments) and negative
 * balances debited from the bank; the only metadata is our merchant id. Requirement strings are grouped into the
 * screen's five lines: identity ({@code individual.*}, {@code representative.*}, {@code person_*}), business
 * ({@code company.*}, {@code business_profile.*}, {@code tos_acceptance.*}), bank ({@code external_account}), owners
 * ({@code owners.*}, {@code *.owners_provided}, {@code directors.*}) and the annual re-verification
 * ({@code future_requirements}). Every POST carries an {@code Idempotency-Key}. Without a key every call answers 409
 * {@code stripe_unavailable}.
 */
class StripeConnectAccountGateway implements ConnectAccountGateway {

    /**
     * @param secretKey Stripe platform secret key
     * @param apiBase API base URL override (stripe-mock locally); blank = Stripe
     */
    @ConfigurationProperties("northline.stripe")
    record Properties(@Nullable String secretKey, @Nullable String apiBase) {}

    private final @Nullable StripeClient client;

    StripeConnectAccountGateway(Properties properties) {
        var key = properties.secretKey();
        this.client = key == null || key.isBlank() ? null : StripeClients.create(key, properties.apiBase());
    }

    /** For tests: a client pointed at stripe-mock. */
    StripeConnectAccountGateway(StripeClient client) {
        this.client = client;
    }

    @Override
    public Optional<Account> account(String accountId) {
        try {
            var a = client().v1().accounts().retrieve(accountId);
            var req = a.getRequirements();
            var future = a.getFutureRequirements();
            var requirements = new ArrayList<Requirement>();
            requirements.add(requirement(Kind.IDENTITY, req, StripeConnectAccountGateway::identity));
            requirements.add(requirement(Kind.BUSINESS, req, StripeConnectAccountGateway::business));
            requirements.add(requirement(Kind.BANK, req, f -> f.startsWith("external_account")));
            requirements.add(requirement(Kind.OWNERS, req, StripeConnectAccountGateway::owners));
            var reverifyDue = future != null
                    && future.getCurrentlyDue() != null
                    && !future.getCurrentlyDue().isEmpty();
            requirements.add(new Requirement(
                    Kind.ANNUAL_REVERIFICATION,
                    reverifyDue ? State.DUE : State.VERIFIED,
                    reverifyDue ? seconds(future.getCurrentDeadline()) : null));
            var settings = a.getSettings();
            var schedule = settings == null || settings.getPayouts() == null
                    ? null
                    : settings.getPayouts().getSchedule();
            var bank = bank(a);
            return Optional.of(new Account(
                    a.getId(),
                    Objects.requireNonNullElse(a.getType(), "express"),
                    Boolean.TRUE.equals(a.getChargesEnabled()),
                    Boolean.TRUE.equals(a.getPayoutsEnabled()),
                    requirements,
                    bank.map(b -> Objects.requireNonNullElse(b.getBankName(), "Bank") + " ··" + b.getLast4())
                            .orElse(null),
                    settings == null || settings.getPayments() == null
                            ? null
                            : settings.getPayments().getStatementDescriptor(),
                    schedule == null ? null : schedule.getInterval(),
                    schedule == null ? null : schedule.getWeeklyAnchor(),
                    bank.map(StripeConnectAccountGateway::instantEligible).orElse(false)));
        } catch (InvalidRequestException e) {
            return Optional.empty();
        } catch (StripeException e) {
            throw unavailable();
        }
    }

    @Override
    public String createExpressAccount(String merchantId) {
        var capability = AccountCreateParams.Capabilities.CardPayments.builder()
                .setRequested(true)
                .build();
        var params = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry("CA")
                .setDefaultCurrency("cad")
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setCardPayments(capability)
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                .setRequested(true)
                                .build())
                        .build())
                .setSettings(AccountCreateParams.Settings.builder()
                        .setPayouts(AccountCreateParams.Settings.Payouts.builder()
                                .setDebitNegativeBalances(true)
                                .setSchedule(AccountCreateParams.Settings.Payouts.Schedule.builder()
                                        .setInterval(AccountCreateParams.Settings.Payouts.Schedule.Interval.MANUAL)
                                        .build())
                                .build())
                        .build())
                .putMetadata("northline_merchant_id", merchantId)
                .build();
        try {
            return client().v1()
                    .accounts()
                    .create(params, key(StripeIdempotencyKeys.of("connect-account", merchantId)))
                    .getId();
        } catch (StripeException e) {
            throw unavailable();
        }
    }

    @Override
    public String dashboardLink(String accountId) {
        try {
            // login links are single-use: a fresh key per click, only stripe-java's own retries share it
            return client().v1()
                    .accounts()
                    .loginLinks()
                    .create(
                            accountId,
                            AccountLoginLinkCreateParams.builder().build(),
                            key(StripeIdempotencyKeys.of("login-link", accountId, Ids.next())))
                    .getUrl();
        } catch (StripeException e) {
            throw unavailable();
        }
    }

    @Override
    public String onboardingLink(String accountId, String returnUrl, String refreshUrl) {
        try {
            return client().v1()
                    .accountLinks()
                    .create(
                            AccountLinkCreateParams.builder()
                                    .setAccount(accountId)
                                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                                    .setCollectionOptions(AccountLinkCreateParams.CollectionOptions.builder()
                                            .setFields(AccountLinkCreateParams.CollectionOptions.Fields.EVENTUALLY_DUE)
                                            .build())
                                    .setReturnUrl(returnUrl)
                                    .setRefreshUrl(refreshUrl)
                                    .build(),
                            key(StripeIdempotencyKeys.of("account-link", accountId, Ids.next())))
                    .getUrl();
        } catch (StripeException e) {
            throw unavailable();
        }
    }

    private StripeClient client() {
        if (client == null) {
            throw new Conflict("stripe_unavailable", "Stripe is not configured.");
        }
        return client;
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static Conflict unavailable() {
        return new Conflict("stripe_unavailable", "Stripe could not be reached. Try again.");
    }

    private static Requirement requirement(Kind kind, @Nullable Requirements req, Predicate<String> matches) {
        if (req == null) {
            return new Requirement(kind, State.VERIFIED, null);
        }
        if (any(req.getPastDue(), matches)) {
            return new Requirement(kind, State.PAST_DUE, seconds(req.getCurrentDeadline()));
        }
        if (any(req.getCurrentlyDue(), matches)) {
            return new Requirement(kind, State.DUE, seconds(req.getCurrentDeadline()));
        }
        if (any(req.getPendingVerification(), matches)) {
            return new Requirement(kind, State.PENDING, null);
        }
        return new Requirement(kind, State.VERIFIED, null);
    }

    private static boolean any(@Nullable List<String> fields, Predicate<String> matches) {
        return fields != null && fields.stream().anyMatch(matches);
    }

    static boolean identity(String field) {
        return field.startsWith("individual.") || field.startsWith("representative.") || field.startsWith("person_");
    }

    static boolean business(String field) {
        return field.startsWith("company.") && !owners(field)
                || field.startsWith("business_profile.")
                || field.startsWith("tos_acceptance.");
    }

    static boolean owners(String field) {
        return field.startsWith("owners.")
                || field.endsWith("owners_provided")
                || field.startsWith("directors.")
                || field.endsWith("directors_provided")
                || field.endsWith("executives_provided");
    }

    private static @Nullable Instant seconds(@Nullable Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    /** The default payout destination (Stripe lists it first); Stripe says whether it takes instant payouts. */
    private static Optional<BankAccount> bank(com.stripe.model.Account a) {
        var external = a.getExternalAccounts();
        if (external == null || external.getData() == null) {
            return Optional.empty();
        }
        return external.getData().stream()
                .filter(BankAccount.class::isInstance)
                .map(BankAccount.class::cast)
                .findFirst();
    }

    private static boolean instantEligible(BankAccount bank) {
        var methods = bank.getAvailablePayoutMethods();
        return methods != null && methods.contains("instant");
    }
}
