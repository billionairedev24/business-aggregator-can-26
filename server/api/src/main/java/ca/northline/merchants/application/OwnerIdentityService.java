package ca.northline.merchants.application;

import ca.northline.merchants.application.OwnerIdentity.ApplyIdentitySession;
import ca.northline.merchants.application.OwnerIdentity.ListOwners;
import ca.northline.merchants.application.OwnerIdentity.OwnerView;
import ca.northline.merchants.application.OwnerIdentity.StartOwnerVerification;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.IdentityCheckStatus;
import ca.northline.merchants.domain.IdentityMatch;
import ca.northline.merchants.domain.IdentitySessionState;
import ca.northline.merchants.domain.OwnerIdentityCheck;
import ca.northline.merchants.domain.OwnerIdentityCheck.Delivery;
import ca.northline.merchants.domain.TeamRules;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stripe Identity for every owner the structure requires (S-22): opens sessions, applies webhook updates and keeps the
 * checklist's {@code kyc} row in step with the owners' results — the row the onboarding checklist and
 * {@code ComplianceStatus} read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class OwnerIdentityService implements ListOwners, StartOwnerVerification, ApplyIdentitySession {

    /** Reference stored on a verified {@code kyc} row (the Studio shows "Passed"). */
    static final String PASSED = "passed";

    private final OwnerIdentityStore store;
    private final VerificationRepository verifications;
    private final MerchantRepository merchants;
    private final IdentityVerification identity;
    private final StudioLinks links;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<OwnerView> owners(String merchantId, String userId) {
        return store.owners(merchantId).stream().map(o -> view(o, userId)).toList();
    }

    @Override
    public Started start(Command command) {
        var merchantId = command.merchantId();
        var owners = store.owners(merchantId);
        var owner = owners.stream()
                .filter(o -> o.principalId().equals(command.principalId()))
                .findFirst()
                .orElseThrow(() -> new NotFound("owner", command.principalId()));
        var email = command.delivery() == Delivery.EMAIL ? email(command.email()) : null;
        if (command.delivery() == Delivery.SELF) {
            requireYou(owners, owner, command.userId());
        }
        var existing = owner.check();
        if (existing != null) {
            existing.requireRestartable();
        }
        var now = clock.instant();
        var checkId = existing == null ? Ids.next() : existing.getId();
        var attempt = existing == null ? 1 : existing.getAttempts() + 1;
        var returnUrl = command.delivery() == Delivery.SELF
                ? links.onboardingVerification(merchantId) + "&identity=returned"
                : links.identityDone();
        var session = identity.start(new IdentityVerification.StartRequest(
                merchantId, checkId, owner.principalId(), attempt, returnUrl, email));
        OwnerIdentityCheck check;
        if (existing == null) {
            check = OwnerIdentityCheck.start(
                    checkId,
                    merchantId,
                    owner.principalId(),
                    session.id(),
                    command.delivery(),
                    email,
                    command.userId(),
                    now);
        } else {
            var replaced = existing.getStripeSession();
            existing.restart(session.id(), command.delivery(), email, command.userId(), now);
            check = existing;
            if (replaced != null) {
                cancelQuietly(replaced);
            }
        }
        store.save(check);
        if (command.delivery() == Delivery.SELF) {
            store.bindUser(merchantId, owner.principalId(), command.userId());
        } else {
            events.publishEvent(new IdentityLinkRequested(
                    Ids.next(), now, merchantId, check.getId(), session.id(), command.userId(), session.url()));
        }
        rollup(merchantId);
        var you = command.delivery() == Delivery.SELF ? command.userId() : owner.userId();
        var updated = new OwnerIdentityStore.Owner(
                owner.principalId(), owner.legalName(), owner.role(), owner.ownershipPct(), you, check);
        return new Started(view(updated, command.userId()), command.delivery() == Delivery.SELF ? session.url() : null);
    }

    @Override
    public void apply(Update update) {
        var check = store.lockBySession(update.sessionId()).orElse(null);
        if (check == null) {
            log.info("Stripe Identity session {} is not a current owner check; ignored", update.sessionId());
            return;
        }
        var nameMatch = IdentityMatch.UNAVAILABLE;
        var dobMatch = IdentityMatch.UNAVAILABLE;
        var state = update.state();
        var lastError = update.lastError();
        if (state == IdentitySessionState.VERIFIED) {
            var owner = store.owners(check.getMerchantId()).stream()
                    .filter(o -> o.principalId().equals(check.getPrincipalId()))
                    .findFirst()
                    .orElse(null);
            if (owner == null) {
                log.info(
                        "Owner {} of {} is no longer on the application; session ignored",
                        check.getPrincipalId(),
                        check.getMerchantId());
                return;
            }
            // the webhook says verified; the matches need the verified outputs, read (and discarded) by the adapter
            var result = identity.read(
                    update.sessionId(),
                    new IdentityVerification.Expected(
                            owner.legalName(),
                            store.stripeAccount(check.getMerchantId()).orElse(null)));
            state = result.state();
            lastError = result.lastError();
            nameMatch = result.nameMatch();
            dobMatch = result.dobMatch();
        }
        var changed = check.apply(
                new OwnerIdentityCheck.SessionUpdate(
                        update.sessionId(), state, lastError, nameMatch, dobMatch, update.stripeCreated()),
                clock.instant());
        if (changed) {
            store.save(check);
            if (check.getStatus() == IdentityCheckStatus.REVIEW) {
                log.warn(
                        "Owner {} of {} verified by Stripe but name {} / date of birth {}: manual review",
                        check.getPrincipalId(),
                        check.getMerchantId(),
                        nameMatch.code(),
                        dobMatch.code());
            }
            rollup(check.getMerchantId());
        }
    }

    /**
     * The {@code kyc} row follows the owners. A verified row with no owner checks at all predates S-22 (dev seed,
     * approvals before this story) and is left as it is.
     */
    void rollup(String merchantId) {
        var kyc = verifications.listFor(merchantId).stream()
                .filter(v -> v.kind() == CheckKind.KYC)
                .findFirst()
                .orElse(null);
        if (kyc == null) {
            return;
        }
        var owners = store.owners(merchantId);
        if (owners.stream().allMatch(o -> o.check() == null) && kyc.getStatus() == VerificationStatus.VERIFIED) {
            return;
        }
        var started = owners.stream()
                .map(OwnerIdentityStore.Owner::check)
                .filter(Objects::nonNull)
                .map(OwnerIdentityCheck::getStatus)
                .toList();
        var next = OwnerIdentityCheck.rollup(owners.size(), started);
        if (kyc.follow(next, next == VerificationStatus.VERIFIED ? PASSED : null, clock.instant())) {
            verifications.save(kyc);
        }
    }

    private void cancelQuietly(String sessionId) {
        try {
            identity.cancel(sessionId);
        } catch (RuntimeException e) {
            log.info("Replaced Stripe Identity session {} could not be canceled: {}", sessionId, e.getMessage());
        }
    }

    /** "This is me": the principal must not belong to someone else, and the user is only one of the owners. */
    private static void requireYou(
            List<OwnerIdentityStore.Owner> owners, OwnerIdentityStore.Owner owner, String userId) {
        if (owner.userId() != null && !owner.userId().equals(userId)) {
            throw new Conflict("identity_not_you", "Another team member is verifying as this owner.");
        }
        owners.stream()
                .filter(o -> userId.equals(o.userId()) && !o.principalId().equals(owner.principalId()))
                .findFirst()
                .ifPresent(o -> {
                    throw new Conflict("identity_already_you", "You're already verifying as " + o.legalName() + ".");
                });
    }

    private static String email(@Nullable String raw) {
        var email = raw == null ? "" : raw.strip();
        if (email.isEmpty()) {
            throw RuleViolation.of(OwnerIdentity.EMAIL_FIELD, "required", OwnerIdentity.EMAIL_REQUIRED);
        }
        if (!TeamRules.EMAIL.matcher(email).matches()) {
            throw RuleViolation.of(OwnerIdentity.EMAIL_FIELD, "format", OwnerIdentity.EMAIL_FORMAT);
        }
        return email.toLowerCase(Locale.ROOT);
    }

    private static OwnerView view(OwnerIdentityStore.Owner o, String userId) {
        var c = o.check();
        return new OwnerView(
                o.principalId(),
                o.legalName(),
                o.role(),
                o.ownershipPct(),
                userId.equals(o.userId()),
                c == null ? "not_started" : c.getStatus().code(),
                c == null ? null : c.getDelivery(),
                c == null ? null : mask(c.getEmail()),
                c == null ? null : c.getLastError(),
                c == null ? null : c.getNameMatch(),
                c == null ? null : c.getDobMatch(),
                c == null ? 0 : c.getAttempts(),
                c == null ? null : c.getUpdatedAt());
    }

    /** {@code ravi@example.com} → {@code r***@example.com}. */
    static @Nullable String mask(@Nullable String email) {
        if (email == null) {
            return null;
        }
        var at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
