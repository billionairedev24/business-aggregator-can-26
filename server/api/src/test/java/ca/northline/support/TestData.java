package ca.northline.support;

import ca.northline.shared.Ids;
import ca.northline.shared.security.MerchantRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts minimal fixture rows with fresh ULIDs so tests never collide on the shared database. Add builders for your
 * module's tables here (or in a module-specific fixture next to your tests) — keep them SQL-level and small.
 */
@TestComponent
@RequiredArgsConstructor
public class TestData {

    private final JdbcClient jdbc;

    /** A merchant plus one member with {@code role}. */
    public record Business(String merchantId, String userId) {}

    public String user(String displayName) {
        var id = Ids.next();
        jdbc.sql("insert into identity.users (id, display_name, locale, status) values (?, ?, 'en-CA', 'active')")
                .params(id, displayName)
                .update();
        return id;
    }

    public String merchant(String type, String displayName) {
        var id = Ids.next();
        jdbc.sql("""
                        insert into merchants.merchants (id, type, display_name, legal_name, structure, tier, status, city)
                        values (?, ?, ?, ?, 'sole', 'registered', 'active', 'Calgary')
                        """).params(id, type, displayName, displayName + " (legal)").update();
        return id;
    }

    public void member(String merchantId, String userId, MerchantRole role) {
        jdbc.sql(
                        "insert into merchants.merchant_members (merchant_id, user_id, role, bookable, mfa_ok) values (?, ?, ?, false, true)")
                .params(merchantId, userId, role.code())
                .update();
    }

    /** Provider business "Prairie Wrench" with one member of the given role. */
    public Business business(MerchantRole role) {
        var merchantId = merchant("provider", "Prairie Wrench");
        var userId = user("Test " + role.code());
        member(merchantId, userId, role);
        return new Business(merchantId, userId);
    }
}
