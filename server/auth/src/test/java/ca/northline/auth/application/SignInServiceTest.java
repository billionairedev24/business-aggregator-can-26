package ca.northline.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.northline.auth.domain.SignInAttempt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S-20: a wrong code for an account that doesn't exist costs the same work as for one that does — the same lookups and,
 * for authenticator codes, a code check — so response times don't reveal which accounts exist.
 */
class SignInServiceTest {

    private final SecondFactors factors = mock(SecondFactors.class);
    private final FlowStore flow = mock(FlowStore.class);
    private final SignInService service = new SignInService(
            mock(UserAccounts.class),
            factors,
            mock(PasskeyService.class),
            flow,
            mock(SignInLog.class),
            mock(
                    AttemptLimits.class,
                    invocation -> invocation.getMethod().getName().equals("failed") ? invocation.getArgument(2) : null),
            mock(FederatedLinking.class),
            Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));

    private final SignInLog.Client client = new SignInLog.Client("203.0.113.7", "test", null);

    @BeforeEach
    void anUnknownAccountWasTyped() {
        when(flow.get(FlowStore.SIGN_IN)).thenReturn(Optional.of(new SignInAttempt("nobody@example.ca", null, 0)));
        when(factors.findTotp(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void authenticatorCode_looksUpASecret_evenForNobody() {
        assertThatThrownBy(() -> service.verifyTotp("123456", client)).isInstanceOf(InvalidInput.class);
        verify(factors).findTotp(anyString());
    }

    @Test
    void backupCode_runsTheSameQuery_evenForNobody() {
        assertThatThrownBy(() -> service.verifyBackupCode("abcde-fghij", client))
                .isInstanceOf(InvalidInput.class);
        verify(factors).consumeBackupCode(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void theDecoyNeverMatchesARealAccount() {
        assertThatThrownBy(() -> service.verifyTotp("123456", client)).isInstanceOf(InvalidInput.class);
        verify(factors).findTotp(eq("-")); // not a ULID: no identity.users row has this id
    }
}
