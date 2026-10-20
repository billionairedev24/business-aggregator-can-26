package ca.northline.payments.infra;

import ca.northline.payments.application.CourierAccountGateway;
import ca.northline.shared.Ids;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import com.stripe.StripeClient;
import com.stripe.model.BankAccount;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;

/**
 * Couriers' Stripe Connect Express accounts through stripe-java (S-151), following the merchants' adapter
 * ({@code StripeConnectAccountGateway}): Canada, CAD, an individual, the {@code transfers} capability only (a courier
 * is paid, never charges a card), Stripe's automatic payouts off (Northline's weekly run creates each payout) and
 * negative balances debited from the bank; the only metadata is our courier's user id. Every POST carries an
 * {@code Idempotency-Key}.
 */
class StripeCourierAccounts implements CourierAccountGateway {

    private final StripeClient stripe;

    StripeCourierAccounts(StripeClient stripe) {
        this.stripe = stripe;
    }

    @Override
    public String createAccount(String courierUserId) {
        var params = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry("CA")
                .setDefaultCurrency("cad")
                .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                .setCapabilities(AccountCreateParams.Capabilities.builder()
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
                .putMetadata("northline_courier_user_id", courierUserId)
                .build();
        return call(
                "courier account",
                () -> stripe.v1()
                        .accounts()
                        .create(params, key(StripeIdempotencyKeys.of("courier-account", courierUserId)))
                        .getId());
    }

    @Override
    public String onboardingLink(String stripeAccount, String returnUrl, String refreshUrl) {
        // account links are single-use: a fresh key per request
        return call(
                "courier account link",
                () -> stripe.v1()
                        .accountLinks()
                        .create(
                                AccountLinkCreateParams.builder()
                                        .setAccount(stripeAccount)
                                        .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                                        .setReturnUrl(returnUrl)
                                        .setRefreshUrl(refreshUrl)
                                        .build(),
                                key(StripeIdempotencyKeys.of("courier-account-link", stripeAccount, Ids.next())))
                        .getUrl());
    }

    @Override
    public Account account(String stripeAccount) {
        var a = call("courier account read", () -> stripe.v1().accounts().retrieve(stripeAccount));
        var due = a.getRequirements() == null || a.getRequirements().getCurrentlyDue() == null
                ? 0
                : a.getRequirements().getCurrentlyDue().size();
        BankAccount bank = null;
        var external = a.getExternalAccounts();
        if (external != null && external.getData() != null) {
            bank = external.getData().stream()
                    .filter(BankAccount.class::isInstance)
                    .map(BankAccount.class::cast)
                    .findFirst()
                    .orElse(null);
        }
        return new Account(
                Boolean.TRUE.equals(a.getPayoutsEnabled()),
                due,
                bank == null ? null : bank.getId(),
                bank == null ? null : bank.getLast4());
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws com.stripe.exception.StripeException;
    }

    private static <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (com.stripe.exception.StripeException e) {
            throw StripeConnectGateway.failure(what, e);
        }
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }
}
