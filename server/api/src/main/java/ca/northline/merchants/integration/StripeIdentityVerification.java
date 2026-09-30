package ca.northline.merchants.integration;

import ca.northline.merchants.application.IdentityVerification;
import ca.northline.merchants.domain.IdentityMatch;
import ca.northline.merchants.domain.IdentitySessionState;
import ca.northline.merchants.domain.PersonNames;
import ca.northline.shared.Conflict;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Person;
import com.stripe.model.identity.VerificationSession;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountPersonListParams;
import com.stripe.param.identity.VerificationSessionCancelParams;
import com.stripe.param.identity.VerificationSessionCreateParams;
import com.stripe.param.identity.VerificationSessionCreateParams.Options.Document.AllowedType;
import com.stripe.param.identity.VerificationSessionRetrieveParams;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Stripe Identity through stripe-java (API version pinned by {@code StripeClients}): a {@code document} session with a
 * matching live selfie (driving licence, passport or ID card, live capture), our check id as
 * {@code client_reference_id}, merchant and principal ids as metadata — nothing personal is sent except the owner's
 * email for emailed links ({@code provided_details}, so Stripe can prefill and send its own receipts).
 *
 * <p>{@link #read} expands {@code verified_outputs} (needs a secret key or a restricted key with Identity read
 * access), compares the name with the principal's legal name and the date of birth with the business's Connect person
 * of the same name, and drops the values: only {@code match | mismatch | unavailable} leaves this class. Every POST
 * carries an {@code Idempotency-Key}; any Stripe failure is 409 {@code identity_unavailable}.
 */
@Slf4j
class StripeIdentityVerification implements IdentityVerification {

    private final StripeClient client;

    StripeIdentityVerification(StripeClient client) {
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
                .setClientReferenceId(request.checkId())
                .putMetadata("northline_merchant_id", request.merchantId())
                .putMetadata("northline_principal_id", request.principalId())
                .setReturnUrl(request.returnUrl());
        if (request.email() != null) {
            params.setProvidedDetails(VerificationSessionCreateParams.ProvidedDetails.builder()
                    .setEmail(request.email())
                    .build());
        }
        try {
            var session = client.v1()
                    .identity()
                    .verificationSessions()
                    .create(
                            params.build(),
                            key(StripeIdempotencyKeys.of(
                                    "identity-session", request.checkId(), String.valueOf(request.attempt()))));
            if (session.getUrl() == null) {
                // Stripe returns the hosted-flow URL while a new session is `requires_input`
                log.warn("Stripe Identity session {} has no hosted-flow URL", session.getId());
                throw unavailable();
            }
            return new Session(session.getId(), session.getUrl());
        } catch (StripeException e) {
            log.warn("Stripe Identity session for {} not created: {}", request.checkId(), e.getMessage());
            throw unavailable();
        }
    }

    @Override
    public void cancel(String sessionId) {
        try {
            client.v1()
                    .identity()
                    .verificationSessions()
                    .cancel(
                            sessionId,
                            VerificationSessionCancelParams.builder().build(),
                            key(StripeIdempotencyKeys.of("identity-cancel", sessionId)));
        } catch (InvalidRequestException e) {
            log.info("Stripe Identity session {} not canceled: {}", sessionId, e.getCode());
        } catch (StripeException e) {
            throw unavailable();
        }
    }

    @Override
    public SessionResult read(String sessionId, Expected expected) {
        try {
            var session = client.v1()
                    .identity()
                    .verificationSessions()
                    .retrieve(
                            sessionId,
                            VerificationSessionRetrieveParams.builder()
                                    .addExpand("verified_outputs")
                                    .build(),
                            RequestOptions.getDefault());
            return result(session, expected);
        } catch (StripeException e) {
            log.warn("Stripe Identity session {} not read: {}", sessionId, e.getMessage());
            throw unavailable();
        }
    }

    private SessionResult result(VerificationSession session, Expected expected) throws StripeException {
        var state = state(session.getStatus());
        var error =
                session.getLastError() == null ? null : session.getLastError().getCode();
        var outputs = session.getVerifiedOutputs();
        if (state != IdentitySessionState.VERIFIED || outputs == null) {
            return new SessionResult(state, error, IdentityMatch.UNAVAILABLE, IdentityMatch.UNAVAILABLE);
        }
        var name = PersonNames.compare(expected.legalName(), outputs.getFirstName(), outputs.getLastName());
        var dob = outputs.getDob() == null
                ? IdentityMatch.UNAVAILABLE
                : dob(
                        expected,
                        outputs.getDob().getYear(),
                        outputs.getDob().getMonth(),
                        outputs.getDob().getDay());
        return new SessionResult(state, error, name, dob);
    }

    /** The verified date of birth against the Connect person with the owner's name (Stripe collected it for payouts). */
    private IdentityMatch dob(Expected expected, @Nullable Long year, @Nullable Long month, @Nullable Long day)
            throws StripeException {
        var account = expected.stripeAccount();
        if (account == null || year == null || month == null || day == null) {
            return IdentityMatch.UNAVAILABLE;
        }
        var persons = client.v1()
                .accounts()
                .persons()
                .list(account, AccountPersonListParams.builder().setLimit(100L).build());
        for (Person p : persons.getData()) {
            if (p.getDob() == null
                    || p.getDob().getYear() == null
                    || PersonNames.compare(expected.legalName(), p.getFirstName(), p.getLastName())
                            != IdentityMatch.MATCH) {
                continue;
            }
            var same = year.equals(p.getDob().getYear())
                    && month.equals(p.getDob().getMonth())
                    && day.equals(p.getDob().getDay());
            return same ? IdentityMatch.MATCH : IdentityMatch.MISMATCH;
        }
        return IdentityMatch.UNAVAILABLE;
    }

    static IdentitySessionState state(@Nullable String status) {
        return Arrays.stream(IdentitySessionState.values())
                .filter(s -> s.code().equals(status))
                .findFirst()
                .orElse(IdentitySessionState.REQUIRES_INPUT);
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static Conflict unavailable() {
        return new Conflict("identity_unavailable", "We couldn't reach Stripe. Try again in a moment.");
    }
}
