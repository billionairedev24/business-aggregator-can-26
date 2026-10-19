package ca.northline.merchants.application;

import ca.northline.identity.api.TeamAccounts;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.Verification;
import ca.northline.shared.security.MerchantRole;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The checklist's "Second factor" row follows the owners' accounts (demo rehearsal 2026-10-19): it is done as soon as
 * an owner has a passkey or an authenticator app — set up at registration or later in Settings › Security — instead of
 * asking them to "Set up passkey" again. Only verifies; a factor removed later doesn't reopen a verified row (the
 * auth server never lets the last one go).
 */
@Component
@RequiredArgsConstructor
class SecondFactorCheck {

    /** {@code identity.users.mfa_primary} values that are a second factor a business account may use. */
    static final Set<String> FACTORS = Set.of("passkey", "totp");

    static final String REFERENCE = "second_factor";

    private final TeamStore team;
    private final TeamAccounts accounts;
    private final VerificationRepository verifications;
    private final Clock clock;

    /** Verifies the open second-factor row of the checklist when an owner has a factor; returns the checklist. */
    List<Verification> settle(String merchantId, List<Verification> checks) {
        var open = checks.stream()
                .filter(c -> c.kind() == CheckKind.MFA && !c.getStatus().complete())
                .toList();
        if (open.isEmpty() || !anOwnerHasAFactor(merchantId)) {
            return checks;
        }
        var now = clock.instant();
        open.forEach(c -> {
            c.verify(REFERENCE, now);
            verifications.save(c);
        });
        return checks;
    }

    private boolean anOwnerHasAFactor(String merchantId) {
        var owners = team.seats(merchantId).stream()
                .filter(s -> s.role() == MerchantRole.OWNER)
                .map(TeamStore.Seat::userId)
                .toList();
        return accounts.accounts(owners).values().stream()
                .map(TeamAccounts.Account::mfaPrimary)
                .filter(Objects::nonNull)
                .anyMatch(FACTORS::contains);
    }
}
