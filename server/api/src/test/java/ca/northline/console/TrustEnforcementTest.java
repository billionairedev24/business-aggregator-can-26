package ca.northline.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.northline.console.application.EnforceTrustRules;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.SettingsFixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S-82: the trust rules' automatic consequences (S-93 configuration) — below the rating floor a business is hidden
 * from search until its average recovers; an off-platform payment mention after a warning suspends it. Each change is
 * in the oversight trail and the audit log, and the owners are emailed.
 */
class TrustEnforcementTest extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EnforceTrustRules enforce;

    String ownerEmail;
    String business;

    @BeforeEach
    void business() {
        var fx = new SettingsFixtures(jdbc);
        ownerEmail = SettingsFixtures.email("owner");
        var owner = fx.person("Kay Garage", ownerEmail, null, "totp");
        business = data.merchant("provider", "S82 Garage on Wheels");
        jdbc.sql("update merchants.merchants set tier = 'registered', status = 'active' where id = ?")
                .params(business)
                .update();
        data.member(business, owner, MerchantRole.OWNER);
    }

    void reviews(int count, int rating) {
        for (var i = 0; i < count; i++) {
            jdbc.sql("""
                            insert into trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id,
                                                       rating, created_at)
                            values (?, 'booking', ?, ?, 'A. Customer', 'merchant', ?, ?, ?)""")
                    .params(
                            Ids.next(),
                            Ids.next(),
                            Ids.next(),
                            business,
                            rating,
                            JdbcTimes.ts(Instant.now().minus(Duration.ofDays(3))))
                    .update();
        }
    }

    /** The hidden-from-search cause, or "" when the business is shown. */
    String hiddenCause() {
        return jdbc.sql("select coalesce(search_hidden_cause, '') from merchants.merchants where id = ?")
                .params(business)
                .query(String.class)
                .single();
    }

    String status() {
        return jdbc.sql("select status from merchants.merchants where id = ?")
                .params(business)
                .query(String.class)
                .single();
    }

    long trail(String action) {
        return jdbc.sql(
                        "select count(*) from merchants.oversight_actions where merchant_id = ? and action = ? and actor_id = 'system'")
                .params(business, action)
                .query(Long.class)
                .single();
    }

    @Test
    void belowTheRatingFloorHidesFromSearchUntilTheAverageRecovers() {
        reviews(5, 3);
        enforce.run();
        assertThat(hiddenCause()).isEqualTo("rating_floor");
        assertThat(trail("search_hidden")).isEqualTo(1);
        assertThat(jdbc.sql(
                                "select count(*) from developer.audit_log where merchant_id = ? and action = 'merchant.search_hidden' and actor_id = 'system'")
                        .params(business)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !emails.to(ownerEmail).isEmpty());
        assertThat(emails.to(ownerEmail).getFirst().subject())
                .isEqualTo("S82 Garage on Wheels is hidden from Northline search");
        assertThat(emails.to(ownerEmail).getFirst().text())
                .contains("Reason: Average rating 3.00 over 90 days is below the floor of 4.2");

        // nothing changes while it stays below
        enforce.run();
        assertThat(trail("search_hidden")).isEqualTo(1);

        // ten 5-star reviews bring the average back above 4.2: shown again
        reviews(10, 5);
        enforce.run();
        assertThat(hiddenCause()).isEmpty();
        assertThat(trail("search_restored")).isEqualTo(1);
    }

    @Test
    void anOffPlatformMentionAfterAWarningSuspends() {
        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, state, merchant_id, action, decided_at, created_at)
                        values (?, 'merchant', ?, 'off_platform_payment', 'actioned', ?, 'warn', ?, ?)""")
                .params(
                        Ids.next(),
                        business,
                        business,
                        JdbcTimes.ts(Instant.now().minus(Duration.ofDays(10))),
                        JdbcTimes.ts(Instant.now().minus(Duration.ofDays(11))))
                .update();
        enforce.run();
        assertThat(status()).isEqualTo("active");

        jdbc.sql("""
                        insert into trust.flags (id, target_type, target_id, rule, state, merchant_id, created_at)
                        values (?, 'merchant', ?, 'off_platform_payment', 'open', ?, ?)""")
                .params(
                        Ids.next(),
                        business,
                        business,
                        JdbcTimes.ts(Instant.now().minus(Duration.ofDays(1))))
                .update();
        enforce.run();
        assertThat(status()).isEqualTo("suspended");
        assertThat(trail("suspended")).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> emails.to(ownerEmail).stream()
                        .anyMatch(m -> m.subject().equals("S82 Garage on Wheels is suspended on Northline")));
        // already suspended: no second action
        enforce.run();
        assertThat(trail("suspended")).isEqualTo(1);
    }
}
