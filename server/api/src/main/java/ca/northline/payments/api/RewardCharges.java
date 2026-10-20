package ca.northline.payments.api;

/**
 * S-152: a provider-funded reward's points are paid by the business. {@link #charge} moves their cost from the
 * business's balance to {@code rewards_funded} in the ledger (Northline routes the points to the customer) and takes
 * it out of the business's money before it is paid out: a reversal of the sale's transfer to its Stripe account when
 * there is one, else the lower balance pays out less. {@link #refund} gives a refunded sale's share back. Each
 * {@code ref} moves money once.
 */
public interface RewardCharges {

    /** @param ref the reward credit's id */
    void charge(String ref, String merchantId, String escrowId, long cents);

    /** @param ref the refund's key (the reward credit and the refund) */
    void refund(String ref, String merchantId, String escrowId, long cents);
}
