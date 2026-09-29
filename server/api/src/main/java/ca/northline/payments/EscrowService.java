package ca.northline.payments;

import org.springframework.stereotype.Service;

@Service
public class EscrowService {
    /** Stripe PaymentIntent, capture_method=manual, idempotency key = eventId. */
    public void authorizeDeposit(String idempotencyKey, String quoteId, String customerId, long cents) {
        // TODO(implement): Stripe Connect call; publish PaymentAuthorized
    }
}
