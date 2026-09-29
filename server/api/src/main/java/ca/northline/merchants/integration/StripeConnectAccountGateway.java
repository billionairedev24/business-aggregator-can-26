package ca.northline.merchants.integration;

import ca.northline.merchants.application.ConnectAccountGateway;
import ca.northline.shared.Conflict;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account.Requirements;
import com.stripe.model.BankAccount;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stripe Connect through stripe-java ({@code northline.stripe.secret-key}). Requirement strings are grouped into the
 * screen's five lines: identity ({@code individual.*}, {@code representative.*}, {@code person_*}), business
 * ({@code company.*}, {@code business_profile.*}, {@code tos_acceptance.*}), bank ({@code external_account}), owners
 * ({@code owners.*}, {@code *.owners_provided}) and the annual re-verification ({@code future_requirements}).
 * Without a key every call answers 409 {@code stripe_unavailable}.
 */
@Component
@Profile("!local & !test")
@EnableConfigurationProperties(StripeConnectAccountGateway.Properties.class)
class StripeConnectAccountGateway implements ConnectAccountGateway {

    @ConfigurationProperties("northline.stripe")
    record Properties(@Nullable String secretKey) {}

    private final @Nullable StripeClient client;

    StripeConnectAccountGateway(Properties properties) {
        var key = properties.secretKey();
        this.client = key == null || key.isBlank() ? null : new StripeClient(key);
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
            return Optional.of(new Account(
                    a.getId(),
                    Objects.requireNonNullElse(a.getType(), "express"),
                    Boolean.TRUE.equals(a.getChargesEnabled()),
                    Boolean.TRUE.equals(a.getPayoutsEnabled()),
                    requirements,
                    bank(a),
                    settings == null || settings.getPayments() == null
                            ? null
                            : settings.getPayments().getStatementDescriptor(),
                    schedule == null ? null : schedule.getInterval(),
                    schedule == null ? null : schedule.getWeeklyAnchor(),
                    true));
        } catch (InvalidRequestException e) {
            return Optional.empty();
        } catch (StripeException e) {
            throw new Conflict("stripe_unavailable", "Stripe could not be reached. Try again.");
        }
    }

    @Override
    public String createExpressAccount(String merchantId) {
        try {
            return client().v1()
                    .accounts()
                    .create(AccountCreateParams.builder()
                            .setType(AccountCreateParams.Type.EXPRESS)
                            .setCountry("CA")
                            .putMetadata("merchant_id", merchantId)
                            .build())
                    .getId();
        } catch (StripeException e) {
            throw new Conflict("stripe_unavailable", "Stripe could not be reached. Try again.");
        }
    }

    @Override
    public String dashboardLink(String accountId) {
        try {
            return client().v1().accounts().loginLinks().create(accountId).getUrl();
        } catch (StripeException e) {
            throw new Conflict("stripe_unavailable", "Stripe could not be reached. Try again.");
        }
    }

    @Override
    public String onboardingLink(String accountId, String returnUrl, String refreshUrl) {
        try {
            return client().v1()
                    .accountLinks()
                    .create(AccountLinkCreateParams.builder()
                            .setAccount(accountId)
                            .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                            .setReturnUrl(returnUrl)
                            .setRefreshUrl(refreshUrl)
                            .build())
                    .getUrl();
        } catch (StripeException e) {
            throw new Conflict("stripe_unavailable", "Stripe could not be reached. Try again.");
        }
    }

    private StripeClient client() {
        if (client == null) {
            throw new Conflict("stripe_unavailable", "Stripe is not configured.");
        }
        return client;
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

    private static boolean identity(String field) {
        return field.startsWith("individual.") || field.startsWith("representative.") || field.startsWith("person_");
    }

    private static boolean business(String field) {
        return field.startsWith("company.") && !owners(field)
                || field.startsWith("business_profile.")
                || field.startsWith("tos_acceptance.");
    }

    private static boolean owners(String field) {
        return field.startsWith("owners.") || field.endsWith("owners_provided") || field.startsWith("directors.");
    }

    private static @Nullable Instant seconds(@Nullable Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    private static @Nullable String bank(com.stripe.model.Account a) {
        var external = a.getExternalAccounts();
        if (external == null || external.getData() == null) {
            return null;
        }
        return external.getData().stream()
                .filter(BankAccount.class::isInstance)
                .map(BankAccount.class::cast)
                .findFirst()
                .map(b -> Objects.requireNonNullElse(b.getBankName(), "Bank") + " ··" + b.getLast4())
                .orElse(null);
    }
}
