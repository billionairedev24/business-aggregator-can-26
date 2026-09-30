package ca.northline.config;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retries email listeners whose provider was down (S-13). A listener that couldn't send throws, Modulith marks its
 * publication failed, and this job resubmits it every {@code northline.notifications.resubmit-every} (10 min) for up
 * to {@value #MAX_ATTEMPTS} attempts (~4 h); the {@code Mailer} skips members already emailed. Scoped to the email
 * listeners — other modules' failed publications keep their own handling (republish on restart). Not under
 * {@code test}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@RequiredArgsConstructor
class FailedEmailResubmission {

    static final int MAX_ATTEMPTS = 24;
    static final List<String> EMAIL_LISTENERS = List.of(".MerchantEmailNotices.", ".TeamInvitationDelivery.");

    private final FailedEventPublications failed;

    @Scheduled(
            fixedDelayString = "${northline.notifications.resubmit-every:PT10M}",
            initialDelayString = "${northline.notifications.resubmit-every:PT10M}")
    void resubmit() {
        try {
            failed.resubmit(ResubmissionOptions.defaults()
                    .withMinAge(Duration.ofMinutes(5))
                    .withBatchSize(100)
                    .withFilter(FailedEmailResubmission::isEmailRetry));
        } catch (RuntimeException e) {
            log.warn("Resubmitting failed email publications failed: {}", e.getMessage());
        }
    }

    static boolean isEmailRetry(EventPublication publication) {
        return publication instanceof TargetEventPublication target
                && EMAIL_LISTENERS.stream()
                        .anyMatch(target.getTargetIdentifier().getValue()::contains)
                && publication.getCompletionAttempts() < MAX_ATTEMPTS;
    }
}
