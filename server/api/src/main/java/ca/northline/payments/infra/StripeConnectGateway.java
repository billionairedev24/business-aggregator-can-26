package ca.northline.payments.infra;

import ca.northline.payments.application.PaymentGateway;
import ca.northline.payments.application.PayoutGateway;
import ca.northline.payments.domain.Payout;
import ca.northline.shared.Ids;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountExternalAccountCreateParams;
import com.stripe.param.AccountExternalAccountUpdateParams;
import com.stripe.param.AccountUpdateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TokenCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferReversalCreateParams;
import com.stripe.param.financialconnections.SessionCreateParams;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Stripe Connect Express through stripe-java (API version pinned by {@code StripeClients}). Separate charges and
 * transfers: PaymentIntents live on the platform account ({@code capture_method=manual}, CAD, Northline is the
 * merchant of record — no {@code on_behalf_of}), transfers go to the connected account in the PaymentIntent's
 * {@code transfer_group} and draw on its charge ({@code source_transaction}), payouts are created on the connected
 * account ({@code Stripe-Account}). Every POST carries an {@code Idempotency-Key}; metadata holds Northline ids only.
 * Active when {@code northline.payments.stripe-secret-key} is set (see {@link PaymentsGatewayConfig}).
 */
class StripeConnectGateway implements PaymentGateway, PayoutGateway {

    private static final String CAD = "cad";

    private final StripeClient stripe;
    private final @Nullable String publishableKey;
    private final AtomicReference<@Nullable String> platformAccount = new AtomicReference<>();

    StripeConnectGateway(StripeClient stripe, @Nullable String publishableKey) {
        this.stripe = stripe;
        this.publishableKey = publishableKey;
    }

    /** A Stripe call failed; the job / request is retried with the same idempotency key. */
    static final class StripeCallFailed extends RuntimeException {
        StripeCallFailed(String what, StripeException cause) {
            super("Stripe " + what + " failed: " + cause.getMessage(), cause);
        }
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (StripeException e) {
            throw new StripeCallFailed(what, e);
        }
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static RequestOptions onAccount(String connectedAccount, String idempotencyKey) {
        return RequestOptions.builder()
                .setStripeAccount(connectedAccount)
                .setIdempotencyKey(idempotencyKey)
                .build();
    }

    // ── charges (platform account) ────────────────────────────────────────────────────────────────────────────────

    @Override
    public String customer(String customerId, String idempotencyKey) {
        return call(
                "customer",
                () -> stripe.v1()
                        .customers()
                        .create(
                                CustomerCreateParams.builder()
                                        .putMetadata("northline_user_id", customerId)
                                        .build(),
                                key(idempotencyKey))
                        .getId());
    }

    @Override
    public Authorization authorize(Authorize request) {
        var params = PaymentIntentCreateParams.builder()
                .setAmount(request.amountCents())
                .setCurrency(CAD)
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .addPaymentMethodType("card")
                .setCustomer(request.stripeCustomer())
                .setTransferGroup(request.transferGroup())
                .putAllMetadata(request.metadata());
        var paymentMethod = request.paymentMethod();
        if (paymentMethod != null) {
            params.setPaymentMethod(paymentMethod).setConfirm(true);
        }
        if (request.offSession()) {
            params.setOffSession(true);
        } else {
            params.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
        }
        return call(
                "payment intent",
                () -> authorization(
                        stripe.v1().paymentIntents().create(params.build(), key(request.idempotencyKey()))));
    }

    @Override
    public Authorization authorization(String stripePaymentIntent) {
        return call(
                "payment intent read",
                () -> authorization(stripe.v1()
                        .paymentIntents()
                        .retrieve(
                                stripePaymentIntent,
                                PaymentIntentRetrieveParams.builder()
                                        .addExpand("latest_charge")
                                        .build())));
    }

    @Override
    public @Nullable String capture(String stripePaymentIntent, long amountCents, String idempotencyKey) {
        var intent = call(
                "capture",
                () -> stripe.v1()
                        .paymentIntents()
                        .capture(
                                stripePaymentIntent,
                                PaymentIntentCaptureParams.builder()
                                        .setAmountToCapture(amountCents)
                                        .build(),
                                key(idempotencyKey)));
        return intent.getLatestCharge();
    }

    @Override
    public void cancel(String stripePaymentIntent, String idempotencyKey) {
        call(
                "cancel",
                () -> stripe.v1()
                        .paymentIntents()
                        .cancel(
                                stripePaymentIntent,
                                PaymentIntentCancelParams.builder()
                                        .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                                        .build(),
                                key(idempotencyKey)));
    }

    @Override
    public String transfer(Transfer transfer) {
        var params = TransferCreateParams.builder()
                .setAmount(transfer.amountCents())
                .setCurrency(CAD)
                .setDestination(transfer.connectedAccount())
                .setTransferGroup(transfer.transferGroup())
                .putAllMetadata(transfer.metadata());
        var source = transfer.sourceCharge();
        if (source != null) {
            params.setSourceTransaction(source);
        }
        return call(
                "transfer",
                () -> stripe.v1()
                        .transfers()
                        .create(params.build(), key(transfer.idempotencyKey()))
                        .getId());
    }

    @Override
    public String reverseTransfer(
            String stripeTransfer, long amountCents, Map<String, String> metadata, String idempotencyKey) {
        return call(
                "transfer reversal",
                () -> stripe.v1()
                        .transfers()
                        .reversals()
                        .create(
                                stripeTransfer,
                                TransferReversalCreateParams.builder()
                                        .setAmount(amountCents)
                                        .putAllMetadata(metadata)
                                        .build(),
                                key(idempotencyKey))
                        .getId());
    }

    @Override
    public String refund(
            String stripePaymentIntent, long amountCents, Map<String, String> metadata, String idempotencyKey) {
        return call(
                "refund",
                () -> stripe.v1()
                        .refunds()
                        .create(
                                RefundCreateParams.builder()
                                        .setPaymentIntent(stripePaymentIntent)
                                        .setAmount(amountCents)
                                        .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                                        .putAllMetadata(metadata)
                                        .build(),
                                key(idempotencyKey))
                        .getId());
    }

    private static Authorization authorization(PaymentIntent pi) {
        var charge = pi.getLatestChargeObject();
        Long captureBefore = null;
        if (charge != null
                && charge.getPaymentMethodDetails() != null
                && charge.getPaymentMethodDetails().getCard() != null) {
            captureBefore = charge.getPaymentMethodDetails().getCard().getCaptureBefore();
        }
        return new Authorization(
                pi.getId(),
                status(pi.getStatus()),
                Objects.requireNonNullElse(pi.getAmount(), 0L),
                Objects.requireNonNullElse(pi.getAmountCapturable(), 0L),
                pi.getClientSecret(),
                pi.getCustomer(),
                pi.getPaymentMethod(),
                pi.getLatestCharge(),
                captureBefore == null ? null : Instant.ofEpochSecond(captureBefore),
                pi.getTransferGroup());
    }

    /** Stripe's PaymentIntent status → ours. */
    static IntentStatus status(@Nullable String stripeStatus) {
        return switch (Objects.requireNonNullElse(stripeStatus, "")) {
            case "requires_capture" -> IntentStatus.AUTHORIZED;
            case "succeeded" -> IntentStatus.CAPTURED;
            case "canceled" -> IntentStatus.CANCELED;
            case "requires_payment_method", "requires_confirmation", "requires_action", "processing" ->
                IntentStatus.REQUIRES_ACTION;
            default -> IntentStatus.FAILED;
        };
    }

    // ── payouts (connected account) ───────────────────────────────────────────────────────────────────────────────

    @Override
    public Sent payout(String connectedAccount, long amountCents, boolean instant, String externalRef, String key) {
        var payout = call(
                "payout",
                () -> stripe.v1()
                        .payouts()
                        .create(
                                PayoutCreateParams.builder()
                                        .setAmount(amountCents)
                                        .setCurrency(CAD)
                                        .setDestination(externalRef)
                                        .setMethod(
                                                instant
                                                        ? PayoutCreateParams.Method.INSTANT
                                                        : PayoutCreateParams.Method.STANDARD)
                                        .setStatementDescriptor("NORTHLINE")
                                        .build(),
                                onAccount(connectedAccount, key)));
        return new Sent(payout.getId(), Instant.ofEpochSecond(Objects.requireNonNull(payout.getArrivalDate())));
    }

    @Override
    public String recoverFee(String connectedAccount, long feeCents, String stripePayout, String idempotencyKey) {
        return call(
                "instant payout fee",
                () -> stripe.v1()
                        .transfers()
                        .create(
                                TransferCreateParams.builder()
                                        .setAmount(feeCents)
                                        .setCurrency(CAD)
                                        .setDestination(platformAccount())
                                        .putMetadata("northline_kind", "instant_payout_fee")
                                        .putMetadata("northline_stripe_payout", stripePayout)
                                        .build(),
                                onAccount(connectedAccount, idempotencyKey))
                        .getId());
    }

    /** The platform's own account id ({@code acct_…}), the destination of account debits; read once. */
    private String platformAccount() throws StripeException {
        var known = platformAccount.get();
        if (known != null) {
            return known;
        }
        var id = stripe.v1().accounts().retrieveCurrent().getId();
        platformAccount.set(id);
        return id;
    }

    @Override
    public Payout.State payoutState(String connectedAccount, String stripePayout) {
        var payout = call(
                "payout read",
                () -> stripe.v1()
                        .payouts()
                        .retrieve(
                                stripePayout,
                                RequestOptions.builder()
                                        .setStripeAccount(connectedAccount)
                                        .build()));
        return payoutState(payout.getStatus());
    }

    /** Stripe payout status → ours ({@code pending}, {@code in_transit}, {@code paid}, {@code failed}, {@code canceled}). */
    static Payout.State payoutState(@Nullable String status) {
        return switch (Objects.requireNonNullElse(status, "")) {
            case "paid" -> Payout.State.PAID;
            case "failed" -> Payout.State.FAILED;
            case "canceled" -> Payout.State.CANCELED;
            case "pending" -> Payout.State.PENDING;
            default -> Payout.State.IN_TRANSIT;
        };
    }

    @Override
    public void useManualPayouts(String connectedAccount) {
        var schedule = AccountUpdateParams.Settings.Payouts.Schedule.builder()
                .setInterval(AccountUpdateParams.Settings.Payouts.Schedule.Interval.MANUAL)
                .build();
        call(
                "payout schedule",
                () -> stripe.v1()
                        .accounts()
                        .update(
                                connectedAccount,
                                AccountUpdateParams.builder()
                                        .setSettings(AccountUpdateParams.Settings.builder()
                                                .setPayouts(AccountUpdateParams.Settings.Payouts.builder()
                                                        .setSchedule(schedule)
                                                        .setDebitNegativeBalances(true)
                                                        .build())
                                                .build())
                                        .build(),
                                key(StripeIdempotencyKeys.of("manual-payouts", connectedAccount))));
    }

    // ── bank accounts (connected account) ────────────────────────────────────────────────────────────────────────

    @Override
    public LinkSession startBankLink(String connectedAccount) {
        var session = call(
                "financial connections session",
                () -> stripe.v1()
                        .financialConnections()
                        .sessions()
                        .create(
                                SessionCreateParams.builder()
                                        .setAccountHolder(SessionCreateParams.AccountHolder.builder()
                                                .setType(SessionCreateParams.AccountHolder.Type.ACCOUNT)
                                                .setAccount(connectedAccount)
                                                .build())
                                        .addPermission(SessionCreateParams.Permission.PAYMENT_METHOD)
                                        .build(),
                                // every click opens a new session; the key only makes stripe-java's own retries safe
                                key(StripeIdempotencyKeys.of("bank-link-session", connectedAccount, Ids.next()))));
        return new LinkSession("stripe", session.getClientSecret(), publishableKey);
    }

    /** {@code linkedAccountRef} is the bank account token Stripe.js' {@code collectBankAccountToken} returned. */
    @Override
    public BankAccount linked(String connectedAccount, String linkedAccountRef) {
        return attach(connectedAccount, linkedAccountRef);
    }

    @Override
    public BankAccount manual(
            String connectedAccount, String institution, String transit, String accountNumber, String holderName) {
        // the same typed details give the same token call; the digest keeps the account number out of the key
        var tokenKey = StripeIdempotencyKeys.of(
                "bank-token",
                connectedAccount,
                digest(institution + '|' + transit + '|' + accountNumber + '|' + holderName));
        var token = call(
                "bank account token",
                () -> stripe.v1()
                        .tokens()
                        .create(
                                TokenCreateParams.builder()
                                        .setBankAccount(TokenCreateParams.BankAccount.builder()
                                                .setCountry("CA")
                                                .setCurrency(CAD)
                                                .setRoutingNumber(transit + "-" + institution)
                                                .setAccountNumber(accountNumber)
                                                .setAccountHolderName(holderName)
                                                .setAccountHolderType(
                                                        TokenCreateParams.BankAccount.AccountHolderType.COMPANY)
                                                .build())
                                        .build(),
                                key(tokenKey)));
        return attach(connectedAccount, token.getId());
    }

    private BankAccount attach(String connectedAccount, String token) {
        var external = call(
                "external account",
                () -> stripe.v1()
                        .accounts()
                        .externalAccounts()
                        .create(
                                connectedAccount,
                                AccountExternalAccountCreateParams.builder()
                                        .setExternalAccount(token)
                                        .build(),
                                key(StripeIdempotencyKeys.of("external-account", connectedAccount, token))));
        var bank = (com.stripe.model.BankAccount) external;
        var routing = Objects.requireNonNullElse(bank.getRoutingNumber(), "");
        var parts = routing.split("-");
        return new BankAccount(
                bank.getId(),
                Objects.requireNonNullElse(bank.getBankName(), "Bank"),
                parts.length == 2 ? parts[1] : null,
                parts.length == 2 ? parts[0] : null,
                bank.getLast4());
    }

    @Override
    public void makeDefault(String connectedAccount, String externalRef) {
        call(
                "default external account",
                () -> stripe.v1()
                        .accounts()
                        .externalAccounts()
                        .update(
                                connectedAccount,
                                externalRef,
                                AccountExternalAccountUpdateParams.builder()
                                        .setDefaultForCurrency(true)
                                        .build(),
                                key(StripeIdempotencyKeys.of(
                                        "default-external-account", connectedAccount, externalRef))));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
