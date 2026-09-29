package ca.northline.payments.application;

import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.PayoutMessages;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Changing the payout bank account, and the end of its 24 h hold. */
@Service
@RequiredArgsConstructor
@Transactional
class BankAccountService implements ChangeBankAccount {

    private final PayoutRepository payouts;
    private final PayoutGateway gateway;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public PayoutGateway.LinkSession linkSession(String merchantId) {
        return gateway.startBankLink(connected(merchantId));
    }

    @Override
    public PayoutAccount prepare(Prepare command) {
        var account = connected(command.merchantId());
        var bank = switch (command.method()) {
            case INSTANT -> {
                if (command.linkedAccountRef() == null
                        || command.linkedAccountRef().isBlank()) {
                    throw RuleViolation.of("linkedAccount", "required", PayoutMessages.LINKED_ACCOUNT_REQUIRED);
                }
                yield gateway.linked(account, command.linkedAccountRef());
            }
            case MANUAL ->
                gateway.manual(
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
                command.userId(),
                clock.instant());
        payouts.insertAccount(draft);
        return draft;
    }

    @Override
    public PayoutAccount confirm(String merchantId, String accountId, String userId) {
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
        events.publishEvent(requested);
        return account;
    }

    /** Pending accounts whose hold ended take over; the previous account is replaced. */
    int activateDue() {
        var now = clock.instant();
        var due = new ArrayList<>(payouts.dueAccounts(now));
        for (var account : due) {
            payouts.activeAccount(account.getMerchantId()).ifPresent(old -> {
                old.replace(now);
                payouts.updateAccount(old);
            });
            var effective = account.activate(now);
            payouts.updateAccount(account);
            payouts.connectedAccount(account.getMerchantId())
                    .ifPresent(c -> gateway.makeDefault(c.stripeAccount(), account.getExternalRef()));
            events.publishEvent(effective);
        }
        return due.size();
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
}
