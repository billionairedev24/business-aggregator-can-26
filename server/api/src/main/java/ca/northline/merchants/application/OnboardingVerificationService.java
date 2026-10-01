package ca.northline.merchants.application;

import ca.northline.merchants.application.ManageApplication.ViewOnboarding;
import ca.northline.merchants.application.VerificationGateways.BankLinking;
import ca.northline.merchants.application.VerificationGateways.Outcome;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.GstNumber;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Verification;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Completes checklist items through the external-system ports (fakes under {@code local}/{@code test}). */
@Service
@RequiredArgsConstructor
@Transactional
class OnboardingVerificationService implements CompleteVerification {

    static final String REFERENCE = "reference";
    static final String DOCUMENT = "documentId";
    static final String CHOICE = "choice";
    static final String EXPIRES = "expiresOn";
    static final String LICENCE_NUMBER = "Enter the licence number.";
    static final String PERMIT_NUMBER = "Enter the permit number.";
    static final String UPLOAD = "Upload the document.";
    static final String EXPIRED = "This document has expired.";
    static final String SIGN = "Read and sign to continue.";
    static final String RETURNS = "Pick a returns policy.";
    static final String PERMITS = "Enter your permit numbers, or confirm none are required.";
    static final String ALCOHOL = "Enter the licence number, or confirm you don't sell alcohol.";
    static final String SLOT = "Pick one of the offered visit slots.";

    private final ApplicationRepository applications;
    private final VerificationRepository verifications;
    private final DocumentRepository documents;
    private final RegistryVerificationService registries;
    private final BankLinking bank;
    private final ViewOnboarding viewOnboarding;
    private final Clock clock;
    private final MerchantPlaces places;

    @Override
    public OnboardingView complete(Command command) {
        var application = applications
                .findById(command.merchantId())
                .orElseThrow(() -> new NotFound("merchant", command.merchantId()));
        var check = verifications
                .find(command.merchantId(), command.verificationId())
                .orElseThrow(() -> new NotFound("verification", command.verificationId()));
        var now = clock.instant();
        var kind = check.kind();
        switch (kind.action()) {
            case null -> throw new IllegalStateException("No action for " + kind);
            case INSTANT -> instant(application, check, kind, now);
            case IDENTITY ->
                throw new Conflict(
                        "identity_per_owner",
                        "Each owner verifies with Stripe Identity: start it from the owners list.");
            case NUMBER -> number(application, check, kind, command.reference(), now);
            case UPLOAD -> upload(check, command, now);
            case SIGN -> {
                if (!"signed".equals(command.choice())) {
                    throw RuleViolation.of(CHOICE, "required", SIGN);
                }
                check.verify("signed", now);
            }
            case CHOOSE -> choose(application, check, kind, command, now);
            case SLOT -> check.submit(slot(command.reference(), now).toString(), null, null, now);
        }
        verifications.save(check);
        return viewOnboarding.view(command.merchantId());
    }

    private void instant(MerchantApplication application, Verification check, CheckKind kind, Instant now) {
        if (kind == CheckKind.REGISTRY) {
            registries.business(application, check, now);
            return;
        }
        var outcome = switch (kind) {
            case BANK -> bank.link(application.getId());
            // Every merchant endpoint requires acr=mfa, so reaching this line proves the second factor.
            default -> new Outcome(true, "second_factor");
        };
        apply(check, outcome, now);
    }

    private void number(
            MerchantApplication application,
            Verification check,
            CheckKind kind,
            @Nullable String reference,
            Instant now) {
        if (kind == CheckKind.GST) {
            var gst = new GstNumberInput(reference).value();
            check.verify(gst, now);
            return;
        }
        var ref = required(reference, kind == CheckKind.AHS_PERMIT ? PERMIT_NUMBER : LICENCE_NUMBER);
        registries.licence(application, check, Objects.requireNonNullElse(check.getRegistry(), kind.key()), ref, now);
    }

    private void upload(Verification check, Command command, Instant now) {
        var docId = command.documentId();
        if (docId == null
                || documents
                        .find(command.merchantId(), docId)
                        .filter(d -> d.purpose() != Document.Purpose.LOGO)
                        .isEmpty()) {
            throw RuleViolation.of(DOCUMENT, "required", UPLOAD);
        }
        Instant expires = null;
        if (command.expiresOn() != null) {
            var zone = places.of(command.merchantId()).zone();
            if (!command.expiresOn().isAfter(LocalDate.ofInstant(now, zone))) {
                throw RuleViolation.of(EXPIRES, "range", EXPIRED);
            }
            expires = command.expiresOn().atStartOfDay(zone).toInstant();
        }
        check.submit(null, docId, expires, now);
    }

    private void choose(
            MerchantApplication application, Verification check, CheckKind kind, Command command, Instant now) {
        var choice = command.choice();
        switch (kind) {
            case RETURNS_POLICY -> {
                if (choice == null || !Set.of("standard", "perishables").contains(choice)) {
                    throw RuleViolation.of(CHOICE, "required", RETURNS);
                }
                check.verify(choice, now);
            }
            case CATEGORY_PERMITS -> {
                if ("none".equals(choice)) {
                    check.verify("none", now);
                } else {
                    check.submit(required(command.reference(), PERMITS), null, null, now);
                }
            }
            case AGLC -> {
                if ("not_applicable".equals(choice)) {
                    check.verify("not_applicable", now);
                } else {
                    registries.licence(application, check, "AGLC", required(command.reference(), ALCOHOL), now);
                }
            }
            default -> throw new IllegalStateException("No choice for " + kind);
        }
    }

    private static void apply(Verification check, Outcome outcome, Instant now) {
        if (outcome.verified()) {
            check.verify(outcome.reference(), now);
        } else {
            check.submit(outcome.reference(), null, null, now);
        }
    }

    /** A kitchen-visit slot: an instant within the next two weeks. */
    private static Instant slot(@Nullable String reference, Instant now) {
        try {
            var at = Instant.parse(required(reference, SLOT));
            if (!at.isAfter(now) || at.isAfter(now.plus(Duration.ofDays(14)))) {
                throw RuleViolation.of(REFERENCE, "range", SLOT);
            }
            return at;
        } catch (DateTimeParseException _) {
            throw RuleViolation.of(REFERENCE, "format", SLOT);
        }
    }

    private static String required(@Nullable String value, String message) {
        if (value == null || value.isBlank()) {
            throw RuleViolation.of(REFERENCE, "required", message);
        }
        return value.strip();
    }

    /** GST entered on the checklist: same rule and messages as the Business step, reported on {@code reference}. */
    private record GstNumberInput(@Nullable String raw) {
        String value() {
            if (raw == null || raw.isBlank()) {
                throw RuleViolation.of(REFERENCE, "required", GstNumber.FORMAT);
            }
            if (!GstNumber.isValid(raw)) {
                throw RuleViolation.of(REFERENCE, "format", GstNumber.FORMAT);
            }
            return new GstNumber(raw).value();
        }
    }
}
