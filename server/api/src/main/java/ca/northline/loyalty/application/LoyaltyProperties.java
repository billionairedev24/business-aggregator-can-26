package ca.northline.loyalty.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.loyalty.*} (S-152; the earning rules themselves are region data, {@code region.loyalty_rules}).
 *
 * @param jobsCron {@code LOYALTY_JOBS_CRON}: the expiry of points, referrals past their window and the local fake's
 *     Plus renewals (platform zone)
 * @param referralWindow a claimed referral rewards only if the invitee's first completed sale comes within this
 * @param newAccountWindow only accounts created within this may claim a code
 * @param claimAttemptsPerHour wrong codes a person may try in an hour
 * @param siteOrigin {@code CONSUMER_ORIGIN}: the consumer site an invite link opens
 */
@ConfigurationProperties("northline.loyalty")
public record LoyaltyProperties(
        @DefaultValue("0 7 * * * *") String jobsCron,
        @DefaultValue("P90D") Duration referralWindow,
        @DefaultValue("P30D") Duration newAccountWindow,
        @DefaultValue("5") int claimAttemptsPerHour,
        @DefaultValue("http://localhost:3000") String siteOrigin) {}
