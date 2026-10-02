package ca.northline.trust.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.allowPrivacyChange;
import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, trust: the reviews a person wrote, the ratings businesses gave them as a customer, and their points.
 *
 * <p>Erasure: a review's author name and words go (V272 lets a privacy change through the reviews' immutability
 * trigger); its star rating stays in the business's rating, linked to nothing but the blanked account's id. Ratings of
 * the person and their points go. The businesses whose reviews changed are re-read by the search projection.
 */
@Component
@RequiredArgsConstructor
class TrustPersonalData implements PersonalDataContributor {

    static final String REVIEW_NAME = "reviewName";
    static final String NAME_LENGTH = "Enter the name as it should appear, up to 80 characters.";

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "trust";
    }

    @Override
    public List<Section> export(Subject subject) {
        var p = Map.of("u", subject.userId());
        return List.of(
                section(jdbc, "trust.reviews", "Reviews you wrote", "Avis que vous avez écrits", """
                        select id, target_type, target_id, rating, tags, text, author_name, lang, job_label, created_at,
                               reply, reply_at
                          from trust.reviews where author_id = :u order by created_at
                        """, p),
                section(jdbc, "trust.ratingsOfYou", "Ratings businesses gave you", "Évaluations reçues", """
                        select id, rating, tags, text, created_at
                          from trust.reviews where target_type = 'customer' and target_id = :u order by created_at
                        """, p),
                section(jdbc, "trust.points", "Points", "Points", """
                        select id, delta, ref_type, ref_id, note, created_at, expires_at
                          from trust.points_ledger where user_id = :u order by created_at
                        """, p));
    }

    @Override
    public Erasure erase(Subject subject) {
        var u = subject.userId();
        allowPrivacyChange(jdbc);
        var merchants = Set.copyOf(
                jdbc.sql("""
                        select distinct target_id from trust.reviews
                         where author_id = :u and target_type = 'merchant' and target_id is not null
                           and (text is not null or author_name is not null)
                        """).param("u", u).query((rs, _) -> rs.getString(1)).list());
        jdbc.sql("""
                        update trust.reviews set text = null, author_name = null
                         where author_id = :u and (text is not null or author_name is not null)
                        """).param("u", u).update();
        jdbc.sql("delete from trust.reviews where target_type = 'customer' and target_id = :u")
                .param("u", u)
                .update();
        jdbc.sql("delete from trust.points_ledger where user_id = :u")
                .param("u", u)
                .update();
        var wrote = jdbc.sql("select exists (select 1 from trust.reviews where author_id = :u)")
                .param("u", u)
                .query(Boolean.class)
                .single();
        var outcome = Erasure.done().touching(merchants);
        return wrote ? outcome.retaining("trust.reviews.ratings", Retention.BUSINESS_RECORDS) : outcome;
    }

    @Override
    public Set<String> correctable() {
        return Set.of(REVIEW_NAME);
    }

    @Override
    public void correct(Subject subject, String field, String value) {
        if (!REVIEW_NAME.equals(field)) {
            throw new IllegalArgumentException("Not correctable here: " + field);
        }
        var name = value.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw RuleViolation.of(REVIEW_NAME, "length", NAME_LENGTH);
        }
        allowPrivacyChange(jdbc);
        jdbc.sql("update trust.reviews set author_name = :name where author_id = :u")
                .param("name", name)
                .param("u", subject.userId())
                .update();
    }
}
