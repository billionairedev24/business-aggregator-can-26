package ca.northline.restricted.adapters;

import ca.northline.restricted.application.AgeIdentityProvider;
import ca.northline.restricted.domain.AgeMessages;
import ca.northline.shared.Conflict;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.identity.VerificationSessionCreateParams;
import com.stripe.param.identity.VerificationSessionCreateParams.Options.Document.AllowedType;
import com.stripe.param.identity.VerificationSessionRedactParams;
import com.stripe.param.identity.VerificationSessionRetrieveParams;
import java.time.LocalDate;
import java.time.Period;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Stripe Identity for customers' age checks: a {@code document} session with a matching live selfie (driving licence,
 * passport or ID card, live capture), the customer's id as {@code client_reference_id} and {@code
 * metadata.northline_purpose=age} — no merchant id, so the owners' checks (S-22) ignore its webhooks. {@link #read}
 * expands {@code verified_outputs.dob} and returns only the age in whole years; the date is dropped. Once the result is
 * kept, {@link #redact} asks Stripe to delete the document, the selfie and the extracted data. Every POST carries an
 * Idempotency-Key; a Stripe failure is 409 {@code age_check_unavailable}. Never run against Stripe (stripe-mock only).
 */
@Slf4j
class StripeAgeIdentity implements AgeIdentityProvider {

    private final StripeClient client;

    StripeAgeIdentity(StripeClient client) {
        this.client = client;
    }

    @Override
    public Session start(StartRequest request) {
        var document = VerificationSessionCreateParams.Options.Document.builder()
                .addAllowedType(AllowedType.DRIVING_LICENSE)
                .addAllowedType(AllowedType.PASSPORT)
                .addAllowedType(AllowedType.ID_CARD)
                .setRequireLiveCapture(true)
                .setRequireMatchingSelfie(true)
                .build();
        var params = VerificationSessionCreateParams.builder()
                .setType(VerificationSessionCreateParams.Type.DOCUMENT)
                .setOptions(VerificationSessionCreateParams.Options.builder()
                        .setDocument(document)
                        .build())
                .setClientReferenceId(request.reference())
                .putMetadata("northline_purpose", "age")
                .setReturnUrl(request.returnUrl())
                .build();
        try {
            var session = client.v1()
                    .identity()
                    .verificationSessions()
                    .create(
                            params,
                            key(StripeIdempotencyKeys.of(
                                    "age-session", request.reference(), String.valueOf(request.attempt()))));
            if (session.getUrl() == null) {
                log.warn("Stripe Identity age session {} has no hosted-flow URL", session.getId());
                throw unavailable();
            }
            return new Session(session.getId(), session.getUrl());
        } catch (StripeException e) {
            log.warn("Stripe Identity age session not created: {}", e.getMessage());
            throw unavailable();
        }
    }

    @Override
    public Result read(String sessionId, LocalDate today) {
        try {
            var session = client.v1()
                    .identity()
                    .verificationSessions()
                    .retrieve(
                            sessionId,
                            VerificationSessionRetrieveParams.builder()
                                    .addExpand("verified_outputs.dob")
                                    .build(),
                            RequestOptions.getDefault());
            var error = session.getLastError() == null
                    ? null
                    : session.getLastError().getCode();
            var outputs = session.getVerifiedOutputs();
            if (!"verified".equals(session.getStatus()) || outputs == null || outputs.getDob() == null) {
                return new Result(String.valueOf(session.getStatus()), error, null);
            }
            var dob = outputs.getDob();
            return new Result("verified", null, age(dob.getYear(), dob.getMonth(), dob.getDay(), today));
        } catch (StripeException e) {
            log.warn("Stripe Identity age session {} not read: {}", sessionId, e.getMessage());
            throw unavailable();
        }
    }

    @Override
    public void redact(String sessionId) {
        try {
            client.v1()
                    .identity()
                    .verificationSessions()
                    .redact(
                            sessionId,
                            VerificationSessionRedactParams.builder().build(),
                            key(StripeIdempotencyKeys.of("age-redact", sessionId)));
        } catch (StripeException e) {
            // the result is kept already; Stripe's own retention applies until a retry (runbook)
            log.warn("Stripe Identity age session {} not redacted: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public String method() {
        return "stripe_identity_document_selfie";
    }

    static @Nullable Integer age(@Nullable Long year, @Nullable Long month, @Nullable Long day, LocalDate today) {
        if (year == null || month == null || day == null) {
            return null;
        }
        var born = LocalDate.of(year.intValue(), month.intValue(), day.intValue());
        return Period.between(born, today).getYears();
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static Conflict unavailable() {
        return new Conflict("age_check_unavailable", AgeMessages.UNAVAILABLE);
    }
}
