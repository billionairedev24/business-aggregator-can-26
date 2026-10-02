package ca.northline.catalogue.persistence;

import ca.northline.shared.privacy.PersonalDataContributor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-105, catalogue: it holds no personal data beyond who started a commerce-platform connection (a ten-minute OAuth
 * state, deleted on erasure) and who uploaded or vetted listing files (ids only — the business's records).
 */
@Component
@RequiredArgsConstructor
class CataloguePersonalData implements PersonalDataContributor {

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "catalogue";
    }

    @Override
    public List<Section> export(Subject subject) {
        return List.of();
    }

    @Override
    public Erasure erase(Subject subject) {
        jdbc.sql("delete from catalogue.commerce_oauth_requests where user_id = :u")
                .param("u", subject.userId())
                .update();
        var uploaded =
                jdbc.sql("""
                        select exists (select 1 from catalogue.listing_documents where uploaded_by = :u)
                            or exists (select 1 from catalogue.imports where created_by = :u)
                        """).param("u", subject.userId()).query(Boolean.class).single();
        return uploaded ? Erasure.done().retaining("catalogue.uploads", Retention.BUSINESS_RECORDS) : Erasure.done();
    }
}
