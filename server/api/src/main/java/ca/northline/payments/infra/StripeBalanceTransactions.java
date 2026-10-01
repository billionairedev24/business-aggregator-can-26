package ca.northline.payments.infra;

import ca.northline.payments.application.StripeBalance;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.param.BalanceTransactionListParams;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@link StripeBalance} through stripe-java: {@code GET /v1/balance_transactions?created[gte]&created[lt]} on the
 * platform account, every page (100 a page). Only CAD is expected; another currency is skipped and logged by the
 * reconciliation as a difference. Written from Stripe's API reference and tested against stripe-mock only.
 */
class StripeBalanceTransactions implements StripeBalance {

    private final StripeClient stripe;

    StripeBalanceTransactions(StripeClient stripe) {
        this.stripe = stripe;
    }

    @Override
    public List<Txn> between(Instant from, Instant to) {
        var params = BalanceTransactionListParams.builder()
                .setCreated(BalanceTransactionListParams.Created.builder()
                        .setGte(from.getEpochSecond())
                        .setLt(to.getEpochSecond())
                        .build())
                .setLimit(100L)
                .build();
        try {
            var out = new ArrayList<Txn>();
            for (BalanceTransaction t : stripe.v1().balanceTransactions().list(params).autoPagingIterable()) {
                if (t.getCurrency() != null && !"cad".equals(t.getCurrency().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(new Txn(
                        t.getId(),
                        t.getType() == null ? "unknown" : t.getType(),
                        t.getSource(),
                        t.getAmount() == null ? 0 : t.getAmount(),
                        t.getFee() == null ? 0 : t.getFee(),
                        Instant.ofEpochSecond(t.getCreated() == null ? from.getEpochSecond() : t.getCreated())));
            }
            out.sort(Comparator.comparing(Txn::createdAt).thenComparing(Txn::id));
            return List.copyOf(out);
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe balance transactions failed: " + e.getMessage(), e);
        }
    }
}
