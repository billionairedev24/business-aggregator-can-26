package ca.northline.payments.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.payments.api.PayoutBankAccounts;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutMessages;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing the payout bank account (linked through {@link BankLinking}), the end of its 24 h hold, and Stripe's
 * word that a Financial Connections link ended. Each change is written to the merchant's audit log
 * ({@code developer.audit_log}) in the same transaction — ids, institution name and last 4 only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class BankAccountService implements ChangeBankAccount, PayoutBankAccounts {

    /** Audit actions (Settings › Security › Audit log). */
    static final String LINKED = "payout_account.linked";

    static final String CONFIRMED = "payout_account.change_confirmed";
    static final String EFFECTIVE = "payout_account.change_effective";
    static final String DISCONNECTED = "payout_account.bank_connection_ended";

    private final PayoutRepository payouts;
    private final PayoutGateway gateway;
    private final BankLinking linking;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public BankLinking.LinkSession linkSession(String merchantId) {
        return linking.start(connected(merchantId));
    }

    @Override
    public PayoutAccount prepare(Prepare command) {
        var account = connected(command.merchantId());
        var bank = switch (command.method()) {
            case INSTANT -> {
                var token = command.linkedAccountRef();
                if (token == null || token.isBlank()) {
                    throw RuleViolation.of("linkedAccount", "required", PayoutMessages.LINKED_ACCOUNT_REQUIRED);
                }
                try {
                    yield linking.link(account, token.strip(), blankToNull(command.financialConnectionsAccount()));
                } catch (BankLinking.NotLinkable e) {
                    log.warn("Bank link for merchant {} refused: {}", command.merchantId(), e.getMessage());
                    throw RuleViolation.of("linkedAccount", "format", PayoutMessages.LINK_AGAIN);
                }
            }
            case MANUAL ->
                linking.manual(
                        account,
                        Objects.requireNonNull(command.institution()),
                        Objects.requireNonNull(command.transit()),
                        Objects.requireNonNull(command.accountNumber()),
                        holder(command));
        };
        var draft = PayoutAccount.draft(
                command.merchantId(),
                command.method(),
                bank.institutionName(),
                bank.institutionNumber(),
                bank.transitNumber(),
                bank.last4(),
                holder(command),
                bank.externalRef(),
                bank.financialConnectionsAccount(),
                command.userId(),
                clock.instant());
        payouts.insertAccount(draft);
        audit.record(AuditTrail.Entry.of(
                        command.merchantId(), command.userId(), command.role(), LINKED, "payout_account", draft.getId())
                .withChange(null, describe(draft)));
        return draft;
    }

    @Override
    public PayoutAccount confirm(String merchantId, String accountId, String userId, String role) {
        var now = clock.instant();
        var account =
                payouts.account(merchantId, accountId).orElseThrow(() -> new NotFound("payout account", accountId));
        payouts.pendingAccount(merchantId)
                .filter(p -> !p.getId().equals(accountId))
                .ifPresent(previous -> {
                    previous.discard();
                    payouts.updateAccount(previous);
                });
        var requested = account.confirm(now);
        payouts.updateAccount(account);
        payouts.bankChanged(merchantId, now);
        var after = new LinkedHashMap<String, Object>(describe(account));
        after.put("stepUp", true);
        after.put("effectiveAt", String.valueOf(account.getEffectiveAt()));
        audit.record(AuditTrail.Entry.of(merchantId, userId, role, CONFIRMED, "payout_account", accountId)
                .withChange(
                        payouts.activeAccount(merchantId)
                                .map(BankAccountService::describe)
                                .orElse(null),
                        after));
        events.publishEvent(requested);
        return account;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> current(String merchantId) {
        return payouts.activeAccount(merchantId)
                .or(() -> payouts.pendingAccount(merchantId))
                .map(PayoutAccount::label);
    }

    /** Pending accounts whose hold ended take over; the previous account is replaced. */
    int activateDue() {
        var now = clock.instant();
        var due = new ArrayList<>(payouts.dueAccounts(now));
        for (var account : due) {
            var old = payouts.activeAccount(account.getMerchantId());
            old.ifPresent(o -> {
                o.replace(now);
                payouts.updateAccount(o);
            });
            var effective = account.activate(now);
            payouts.updateAccount(account);
            payouts.connectedAccount(account.getMerchantId())
                    .ifPresent(c -> gateway.makeDefault(c.stripeAccount(), account.getExternalRef()));
            audit.record(AuditTrail.Entry.of(
                            account.getMerchantId(), "system", "system", EFFECTIVE, "payout_account", account.getId())
                    .withChange(old.map(BankAccountService::describe).orElse(null), describe(account)));
            events.publishEvent(effective);
        }
        return due.size();
    }

    /**
     * {@code financial_connections.account.disconnected} / {@code …deactivated}: the owner revoked Northline's access at
     * their bank (or it lapsed). The bank account stays the connected account's external account, so payouts keep going
     * there; the Studio shows the link ended and offers to reconnect. False when no payout account came from it.
     */
    boolean connectionEnded(StripeEvent event) {
        var fca = event.object().id();
        if (fca == null) {
            return false;
        }
        var linked = payouts.accountsLinkedTo(fca);
        for (var account : linked) {
            if (account.connectionEnded(event.created())) {
                payouts.updateAccount(account);
                var after = new LinkedHashMap<String, Object>(describe(account));
                after.put("status", Objects.requireNonNullElse(event.object().text("status"), "disconnected"));
                audit.record(AuditTrail.Entry.of(
                                account.getMerchantId(),
                                "stripe",
                                "system",
                                DISCONNECTED,
                                "payout_account",
                                account.getId())
                        .withChange(null, after));
            }
        }
        return !linked.isEmpty();
    }

    /** What the audit log keeps about an account: never the account number. */
    private static Map<String, Object> describe(PayoutAccount a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("method", a.getMethod().code());
        m.put("institution", a.getInstitutionName());
        m.put("last4", a.getLast4());
        m.put("state", a.getState().code());
        return m;
    }

    private String connected(String merchantId) {
        return payouts.connectedAccount(merchantId)
                .map(PayoutRepository.ConnectedAccount::stripeAccount)
                .orElseThrow(() -> new Conflict("no_connected_account", "Finish Stripe onboarding first."));
    }

    /** Must match the legal entity on the Stripe account; defaults to the current account's holder. */
    private String holder(Prepare command) {
        if (command.holderName() != null && !command.holderName().isBlank()) {
            return command.holderName().strip();
        }
        return payouts.activeAccount(command.merchantId())
                .map(PayoutAccount::getHolderName)
                .orElseThrow(() -> RuleViolation.of("holderName", "required", PayoutMessages.HOLDER_REQUIRED));
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
