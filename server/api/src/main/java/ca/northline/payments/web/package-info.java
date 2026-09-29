/**
 * REST adapters of the Finance screens under {@code /api/v1/merchants/{merchantId}/} {@code earnings}, {@code reports},
 * {@code payouts}, {@code refunds} and {@code disputes}. Money-moving POSTs take an {@code Idempotency-Key} header
 * ({@link ca.northline.payments.web.PaymentsIdempotency}); payouts and bank changes also an {@code X-Step-Up} proof.
 */
@NullMarked
package ca.northline.payments.web;

import org.jspecify.annotations.NullMarked;
