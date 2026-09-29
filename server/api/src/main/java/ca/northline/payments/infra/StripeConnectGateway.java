package ca.northline.payments.infra;

import ca.northline.payments.application.PaymentGateway;
import ca.northline.payments.application.PayoutGateway;
import ca.northline.payments.domain.PayoutSchedule;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountExternalAccountCreateParams;
import com.stripe.param.AccountExternalAccountUpdateParams;
import com.stripe.param.AccountUpdateParams;
import com.stripe.param.AccountUpdateParams.Settings.Payouts.Schedule;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TokenCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.financialconnections.SessionCreateParams;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Stripe Connect Express through stripe-java. Active only when {@code northline.payments.stripe-secret-key} is set
 * (see {@link PaymentsGatewayConfig}); account numbers are tokenized at Stripe and never stored.
 */
@RequiredArgsConstructor
class StripeConnectGateway implements PaymentGateway, PayoutGateway {

    private static final String CAD = "cad";

    private final StripeClient stripe;
    private final @Nullable String publishableKey;

    /** A Stripe call failed; the job / request is retried with the same idempotency key. */
    static final class StripeCallFailed extends RuntimeException {
        StripeCallFailed(String what, StripeException cause) {
            super("Stripe " + what + " failed: " + cause.getMessage(), cause);
        }
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    @Override
    public void capture(String stripePaymentIntent, long amountCents, String idempotencyKey) {
        try {
            stripe.paymentIntents()
                    .capture(
                            stripePaymentIntent,
                            PaymentIntentCaptureParams.builder()
                                    .setAmountToCapture(amountCents)
                                    .build(),
                            key(idempotencyKey));
        } catch (StripeException e) {
            throw new StripeCallFailed("capture", e);
        }
    }

    @Override
    public String transfer(String connectedAccount, long netCents, String escrowId, String idempotencyKey) {
        try {
            return stripe.transfers()
                    .create(
                            TransferCreateParams.builder()
                                    .setAmount(netCents)
                                    .setCurrency(CAD)
                                    .setDestination(connectedAccount)
                                    .setTransferGroup(escrowId)
                                    .build(),
                            key(idempotencyKey))
                    .getId();
        } catch (StripeException e) {
            throw new StripeCallFailed("transfer", e);
        }
    }

    @Override
    public String refund(String stripePaymentIntent, long amountCents, String idempotencyKey) {
        try {
            return stripe.refunds()
                    .create(
                            RefundCreateParams.builder()
                                    .setPaymentIntent(stripePaymentIntent)
                                    .setAmount(amountCents)
                                    .build(),
                            key(idempotencyKey))
                    .getId();
        } catch (StripeException e) {
            throw new StripeCallFailed("refund", e);
        }
    }

    @Override
    public Sent payout(String connectedAccount, long amountCents, boolean instant, String externalRef, String key) {
        try {
            var payout = stripe.payouts()
                    .create(
                            PayoutCreateParams.builder()
                                    .setAmount(amountCents)
                                    .setCurrency(CAD)
                                    .setDestination(externalRef)
                                    .setMethod(
                                            instant
                                                    ? PayoutCreateParams.Method.INSTANT
                                                    : PayoutCreateParams.Method.STANDARD)
                                    .build(),
                            RequestOptions.builder()
                                    .setStripeAccount(connectedAccount)
                                    .setIdempotencyKey(key)
                                    .build());
            return new Sent(payout.getId(), Instant.ofEpochSecond(Objects.requireNonNull(payout.getArrivalDate())));
        } catch (StripeException e) {
            throw new StripeCallFailed("payout", e);
        }
    }

    @Override
    public LinkSession startBankLink(String connectedAccount) {
        try {
            var session = stripe.financialConnections()
                    .sessions()
                    .create(SessionCreateParams.builder()
                            .setAccountHolder(SessionCreateParams.AccountHolder.builder()
                                    .setType(SessionCreateParams.AccountHolder.Type.ACCOUNT)
                                    .setAccount(connectedAccount)
                                    .build())
                            .addPermission(SessionCreateParams.Permission.PAYMENT_METHOD)
                            .build());
            return new LinkSession("stripe", session.getClientSecret(), publishableKey);
        } catch (StripeException e) {
            throw new StripeCallFailed("financial connections session", e);
        }
    }

    /** {@code linkedAccountRef} is the bank account token Stripe.js' {@code collectBankAccountToken} returned. */
    @Override
    public BankAccount linked(String connectedAccount, String linkedAccountRef) {
        return attach(connectedAccount, linkedAccountRef);
    }

    @Override
    public BankAccount manual(
            String connectedAccount, String institution, String transit, String accountNumber, String holderName) {
        try {
            var token = stripe.tokens()
                    .create(TokenCreateParams.builder()
                            .setBankAccount(TokenCreateParams.BankAccount.builder()
                                    .setCountry("CA")
                                    .setCurrency(CAD)
                                    .setRoutingNumber(transit + "-" + institution)
                                    .setAccountNumber(accountNumber)
                                    .setAccountHolderName(holderName)
                                    .setAccountHolderType(TokenCreateParams.BankAccount.AccountHolderType.COMPANY)
                                    .build())
                            .build());
            return attach(connectedAccount, token.getId());
        } catch (StripeException e) {
            throw new StripeCallFailed("bank account token", e);
        }
    }

    private BankAccount attach(String connectedAccount, String token) {
        try {
            var external = stripe.accounts()
                    .externalAccounts()
                    .create(
                            connectedAccount,
                            AccountExternalAccountCreateParams.builder()
                                    .setExternalAccount(token)
                                    .build());
            var bank = (com.stripe.model.BankAccount) external;
            var routing = Objects.requireNonNullElse(bank.getRoutingNumber(), "");
            var parts = routing.split("-");
            return new BankAccount(
                    bank.getId(),
                    Objects.requireNonNullElse(bank.getBankName(), "Bank"),
                    parts.length == 2 ? parts[1] : null,
                    parts.length == 2 ? parts[0] : null,
                    bank.getLast4());
        } catch (StripeException e) {
            throw new StripeCallFailed("external account", e);
        }
    }

    @Override
    public void makeDefault(String connectedAccount, String externalRef) {
        try {
            stripe.accounts()
                    .externalAccounts()
                    .update(
                            connectedAccount,
                            externalRef,
                            AccountExternalAccountUpdateParams.builder()
                                    .setDefaultForCurrency(true)
                                    .build());
        } catch (StripeException e) {
            throw new StripeCallFailed("default external account", e);
        }
    }

    @Override
    public void updateSchedule(String connectedAccount, PayoutSchedule schedule) {
        var builder = Schedule.builder();
        switch (schedule.frequency()) {
            case DAILY -> builder.setInterval(Schedule.Interval.DAILY);
            case WEEKLY ->
                builder.setInterval(Schedule.Interval.WEEKLY)
                        .setWeeklyAnchor(Schedule.WeeklyAnchor.valueOf(
                                java.time.DayOfWeek.of(Objects.requireNonNull(schedule.weekday()))
                                        .name()));
            case MONTHLY ->
                builder.setInterval(Schedule.Interval.MONTHLY)
                        .setMonthlyAnchor(
                                switch (Objects.requireNonNull(schedule.monthlyAnchor())) {
                                    case FIRST -> 1L;
                                    case FIFTEENTH -> 15L;
                                    case LAST -> 31L;
                                });
            default -> builder.setInterval(Schedule.Interval.MANUAL);
        }
        try {
            stripe.accounts()
                    .update(
                            connectedAccount,
                            AccountUpdateParams.builder()
                                    .setSettings(AccountUpdateParams.Settings.builder()
                                            .setPayouts(AccountUpdateParams.Settings.Payouts.builder()
                                                    .setSchedule(builder.build())
                                                    .build())
                                            .build())
                                    .build());
        } catch (StripeException e) {
            throw new StripeCallFailed("payout schedule", e);
        }
    }
}
