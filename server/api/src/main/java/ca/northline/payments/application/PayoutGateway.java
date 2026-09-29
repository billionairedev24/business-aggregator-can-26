package ca.northline.payments.application;

import ca.northline.payments.domain.PayoutSchedule;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: payouts and bank accounts on a merchant's Stripe connected account. Account numbers pass through to
 * Stripe and are never stored; Northline keeps Stripe's reference and the last four digits.
 */
public interface PayoutGateway {

    /** A payout Stripe accepted. */
    record Sent(String payoutId, Instant arrivesAt) {}

    /** How the Studio links a bank "instantly": Stripe Financial Connections, or the local fake. */
    record LinkSession(
            String mode,
            @Nullable String clientSecret,
            @Nullable String publishableKey) {}

    /** A bank account as Stripe knows it. */
    record BankAccount(
            String externalRef,
            String institutionName,
            @Nullable String institutionNumber,
            @Nullable String transitNumber,
            String last4) {}

    Sent payout(String connectedAccount, long amountCents, boolean instant, String externalRef, String idempotencyKey);

    LinkSession startBankLink(String connectedAccount);

    /** The account the owner picked in Financial Connections ({@code fca_…}). */
    BankAccount linked(String connectedAccount, String linkedAccountRef);

    /** Typed institution / transit / account: tokenized at Stripe. */
    BankAccount manual(
            String connectedAccount, String institution, String transit, String accountNumber, String holderName);

    /** Payouts go to this account from now on. */
    void makeDefault(String connectedAccount, String externalRef);

    void updateSchedule(String connectedAccount, PayoutSchedule schedule);
}
