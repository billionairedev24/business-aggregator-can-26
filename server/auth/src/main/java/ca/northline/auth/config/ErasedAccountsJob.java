package ca.northline.auth.config;

import ca.northline.auth.application.ErasedAccounts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-105: every five minutes, forgets the passkeys, authenticator secrets, backup codes, linked identities and OAuth
 * authorizations of accounts the api's privacy pipeline has erased. Tokens are refused from the moment the account is
 * closed ({@code UserClaimsService.requireNotErased}); this removes the rows. Not under {@code test}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class ErasedAccountsJob {

    static final int BATCH = 100;

    private final ErasedAccounts accounts;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
    void purge() {
        try {
            var purged = accounts.purge(BATCH);
            if (purged > 0) {
                log.info("Auth data of {} erased account(s) removed", purged);
            }
        } catch (RuntimeException e) {
            log.error("Removing erased accounts' auth data failed; retrying in five minutes", e);
        }
    }
}
