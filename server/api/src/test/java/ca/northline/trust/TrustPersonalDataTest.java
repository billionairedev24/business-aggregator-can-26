package ca.northline.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor.Kept;
import ca.northline.shared.privacy.PersonalDataContributor.Retention;
import ca.northline.support.PersonalDataTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * S-105: trust's part — a review's author name and words go (through V272's privacy door in the immutability trigger),
 * its rating stays; ratings of the person and their points go; the reviewed business is reported for a search refresh.
 */
class TrustPersonalDataTest extends PersonalDataTest {

    @Override
    protected String module() {
        return "trust";
    }

    @Test
    void erasureBlanksReviewsKeepsRatingsAndDeletesPoints() {
        var person = person();
        var u = person.userId();
        var merchant = data.merchant("provider", "Prairie Wrench");
        var review = Ids.next();
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating, text,
                                                   author_name, lang)
                        values (:id, 'booking', :ref, :u, 'merchant', :m, 4, 'Ravi was great, call me 403-555-0148',
                                'Dana K.', 'en'),
                               (:other, 'booking', :ref, :m, 'customer', :u, 5, 'Lovely customer', 'Ravi', 'en')
                        """)
                .param("id", review)
                .param("other", Ids.next())
                .param("ref", Ids.next())
                .param("u", u)
                .param("m", merchant)
                .update();
        jdbc.sql("insert into trust.points_ledger (id, user_id, delta, ref_type) values (:id, :u, 120, 'order')")
                .param("id", Ids.next())
                .param("u", u)
                .update();

        assertThat(section(person, "trust.reviews").orElseThrow().json()).contains("call me");
        assertThat(records(person, "trust.ratingsOfYou")).isEqualTo(1);
        assertThat(records(person, "trust.points")).isEqualTo(1);

        var outcome = erase(person);

        assertThat(text(
                        "select concat_ws('|', text, author_name, rating) from trust.reviews where id = :id",
                        Map.of("id", review)))
                .isEqualTo("4");
        assertThat(count(
                        "select count(*) from trust.reviews where target_type = 'customer' and target_id = :u",
                        Map.of("u", u)))
                .isZero();
        assertThat(count("select count(*) from trust.points_ledger where user_id = :u", Map.of("u", u)))
                .isZero();
        assertThat(outcome.merchantIds()).containsExactly(merchant);
        assertThat(outcome.retained()).containsExactly(new Kept<>("trust.reviews.ratings", Retention.BUSINESS_RECORDS));
        var again = erase(person);
        assertThat(again.merchantIds()).isEmpty();
        assertThat(again.retained()).isEqualTo(outcome.retained());
    }

    @Test
    void outsideAPrivacyRequestAReviewStaysImmutable() {
        var person = person();
        var review = Ids.next();
        jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, target_type, target_id, rating, text,
                                                   author_name, lang)
                        values (:id, 'order', :ref, :u, 'merchant', 'm1', 3, 'ok', 'Dana K.', 'en')
                        """)
                .param("id", review)
                .param("ref", Ids.next())
                .param("u", person.userId())
                .update();

        assertThatThrownBy(() -> jdbc.sql("update trust.reviews set author_name = null where id = :id")
                        .param("id", review)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        inTransaction(() -> {
            contributor().correct(person, "reviewName", "Dana Kowalska");
            return true;
        });
        assertThat(text("select author_name from trust.reviews where id = :id", Map.of("id", review)))
                .isEqualTo("Dana Kowalska");
    }
}
