package ca.northline.payments.infra;

import ca.northline.payments.application.BankLinking;
import ca.northline.shared.Ids;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountExternalAccountCreateParams;
import com.stripe.param.TokenCreateParams;
import com.stripe.param.financialconnections.SessionCreateParams;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Bank linking through stripe-java (API version pinned by {@code StripeClients}). Financial Connections: a session
 * whose account holder is the merchant's connected account, permission {@code payment_method}; the Studio's Stripe.js
 * {@code collectBankAccountToken} returns a bank-account token and the Financial Connections account; the token is
 * attached as the connected account's external account (payouts go there once it becomes the default after the 24 h
 * hold). The Financial Connections account must belong to the same connected account and be active. Typed details are
 * tokenized ({@code tokens}) and attached the same way. Every POST carries an {@code Idempotency-Key}. Written against
 * Stripe's documented API and tested with stripe-mock only — never run against a real Stripe account.
 */
class StripeBankLinking implements BankLinking {

    private final StripeClient stripe;
    private final @Nullable String publishableKey;

    StripeBankLinking(StripeClient stripe, @Nullable String publishableKey) {
        this.stripe = stripe;
        this.publishableKey = publishableKey;
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (InvalidRequestException e) {
            // an unknown / used token or account: the owner links again
            throw new NotLinkable("Stripe refused the " + what + ": " + e.getCode());
        } catch (StripeException e) {
            throw StripeConnectGateway.failure(what, e);
        }
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    @Override
    public LinkSession start(String connectedAccount) {
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

    @Override
    public Linked link(String connectedAccount, String bankToken, @Nullable String financialConnectionsAccount) {
        if (financialConnectionsAccount != null) {
            var fca = call(
                    "financial connections account",
                    () -> stripe.v1().financialConnections().accounts().retrieve(financialConnectionsAccount));
            var holder = fca.getAccountHolder();
            if (holder == null || !connectedAccount.equals(holder.getAccount())) {
                throw new NotLinkable("Financial Connections account " + financialConnectionsAccount + " isn't held by "
                        + connectedAccount);
            }
            if (!"active".equals(fca.getStatus())) {
                throw new NotLinkable(
                        "Financial Connections account " + financialConnectionsAccount + " is " + fca.getStatus());
            }
        }
        var bank = attach(connectedAccount, bankToken);
        return new Linked(
                bank.getId(),
                Objects.requireNonNullElse(bank.getBankName(), "Bank"),
                bank.getLast4(),
                null,
                null,
                financialConnectionsAccount);
    }

    @Override
    public Linked manual(
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
                                                .setCurrency("cad")
                                                .setRoutingNumber(transit + "-" + institution)
                                                .setAccountNumber(accountNumber)
                                                .setAccountHolderName(holderName)
                                                .setAccountHolderType(
                                                        TokenCreateParams.BankAccount.AccountHolderType.COMPANY)
                                                .build())
                                        .build(),
                                key(tokenKey)));
        var bank = attach(connectedAccount, token.getId());
        var routing = Objects.requireNonNullElse(bank.getRoutingNumber(), "");
        var parts = routing.split("-");
        return new Linked(
                bank.getId(),
                Objects.requireNonNullElse(bank.getBankName(), "Bank"),
                bank.getLast4(),
                parts.length == 2 ? parts[1] : institution,
                parts.length == 2 ? parts[0] : transit,
                null);
    }

    /** The token becomes an external account of the connected account (not the default yet: the 24 h hold). */
    private com.stripe.model.BankAccount attach(String connectedAccount, String token) {
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
        if (!(external instanceof com.stripe.model.BankAccount bank)) {
            throw new NotLinkable("Stripe attached a " + external.getClass().getSimpleName() + ", not a bank account");
        }
        return bank;
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
