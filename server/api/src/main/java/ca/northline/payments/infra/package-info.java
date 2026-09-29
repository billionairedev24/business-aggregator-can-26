/**
 * Adapters to the outside world: Stripe Connect (real, only with keys) and the local fake, step-up proof verification
 * (JWT from northline-auth; plus the {@code dev} proof under local/test), dispute evidence storage, and the scheduler
 * that runs {@link ca.northline.payments.application.PaymentsJobs}.
 */
@NullMarked
package ca.northline.payments.infra;

import org.jspecify.annotations.NullMarked;
