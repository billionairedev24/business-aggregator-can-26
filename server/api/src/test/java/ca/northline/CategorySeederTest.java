package ca.northline;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.support.IntegrationTest;
import ca.northline.tools.CategorySeeder;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CategorySeederTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Test
    void seedsGroupsThenLeaves_idempotently() {
        int first = new CategorySeeder(dataSource).seed();
        int second = new CategorySeeder(dataSource).seed();

        assertThat(first).isEqualTo(second).isGreaterThan(150);
        assertThat(jdbc.sql("select count(*) from catalogue.categories")
                        .query(Integer.class)
                        .single())
                .isEqualTo(first);
        assertThat(jdbc.sql(
                                "select parent_id, regulated_registry, root, name_i18n->>'en' from catalogue.categories where id = ?")
                        .param("service.automotive.mobile-mechanic")
                        .query((rs, _) ->
                                rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4))
                        .single())
                .isEqualTo("service.automotive|AMVIC|service|Mobile mechanic");
        assertThat(jdbc.sql("select count(*) from catalogue.categories where parent_id is null")
                        .query(Integer.class)
                        .single())
                .isEqualTo(20);
    }
}
