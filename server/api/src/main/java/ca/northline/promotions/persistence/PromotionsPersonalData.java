package ca.northline.promotions.persistence;

import static ca.northline.shared.privacy.PersonalDataSql.section;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, promotions: the codes and points a person used at checkout. Erasure keeps them — they are part of the sales'
 * money (what the customer paid, what Northline or the business funded: tax and financial records) and hold no name,
 * contact or words; only the account id, which identity blanks.
 */
@Component
@RequiredArgsConstructor
class PromotionsPersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "promotions";
    }

    @Override
    public List<Section> export(Subject subject) {
        return List.of(section(
                jdbc,
                "promotions.redemptions",
                "Promo codes and points you used",
                "Codes promo et points utilisés",
                """
                select r.kind, r.ref_id, c.code, r.discount_cents, r.points, r.points_cents, r.state, r.created_at,
                       r.redeemed_at
                  from promotions.redemptions r left join promotions.codes c on c.id = r.code_id
                 where r.customer_id = :u order by r.created_at
                """,
                Map.of("u", subject.userId())));
    }

    @Override
    public Erasure erase(Subject subject) {
        var used = jdbc.sql("select exists (select 1 from promotions.redemptions where customer_id = :u)")
                .param("u", subject.userId())
                .query(Boolean.class)
                .single();
        return used ? Erasure.done().retaining("promotions.redemptions", Retention.TAX_RECORDS) : Erasure.done();
    }
}
