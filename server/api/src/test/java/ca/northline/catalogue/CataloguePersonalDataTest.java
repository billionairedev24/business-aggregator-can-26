package ca.northline.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-105: catalogue's part — a pending commerce connection goes; who uploaded listing files stays the business's. */
class CataloguePersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "catalogue";
    }

    @Test
    void pendingConnectionsGoAndUploadsStayTheBusinesss() {
        var person = person();
        jdbc.sql("""
                        insert into catalogue.commerce_oauth_requests (state_hash, merchant_id, user_id, provider,
                                                                       created_at, expires_at)
                        values (:h, 'm1', :u, 'square', now(), now() + interval '10 minutes')
                        """).param("h", "h-" + Ids.next()).param("u", person.userId()).update();

        assertThat(contributor().export(person)).isEmpty();
        var outcome = erase(person);

        assertThat(count(
                        "select count(*) from catalogue.commerce_oauth_requests where user_id = :u",
                        Map.of("u", person.userId())))
                .isZero();
        assertThat(outcome.retained()).isEmpty();

        var uploader = person();
        jdbc.sql("""
                        insert into catalogue.imports (id, merchant_id, file_name, template, row_count, status,
                                                       created_by)
                        values (:id, 'm1', 'items.csv', 'auto_parts', 1, 'validated', :u)
                        """).param("id", Ids.next()).param("u", uploader.userId()).update();
        assertThat(erase(uploader).retained())
                .containsExactly(new Kept<>("catalogue.uploads", Retention.BUSINESS_RECORDS));
    }
}
