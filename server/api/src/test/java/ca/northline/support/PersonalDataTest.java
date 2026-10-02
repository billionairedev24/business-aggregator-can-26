package ca.northline.support;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.Ids;
import ca.northline.shared.privacy.PersonalDataContributor;
import ca.northline.shared.privacy.PersonalDataContributor.Erasure;
import ca.northline.shared.privacy.PersonalDataContributor.Section;
import ca.northline.shared.privacy.PersonalDataContributor.Subject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the S-105 contributor tests: one per module that keeps personal data. Each inserts its own rows for a fresh
 * person, then checks the export holds them and that erasure blanks or keeps them as the module documents — and that
 * erasing twice changes nothing more (the pipeline retries steps). Erasure runs in a transaction, as in the pipeline.
 */
public abstract class PersonalDataTest extends IntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private List<PersonalDataContributor> contributors;

    @Autowired
    private TransactionTemplate transactions;

    /** The module under test. */
    protected abstract String module();

    protected PersonalDataContributor contributor() {
        return contributors.stream()
                .filter(c -> c.module().equals(module()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no contributor for " + module()));
    }

    /** A fresh account with a name, an email and a mobile number. */
    protected Subject person() {
        var id = Ids.next();
        var email = "p-" + id.toLowerCase(Locale.ROOT) + "@example.ca";
        var phone = "+1403%07d".formatted(ThreadLocalRandom.current().nextInt(2_000_000, 9_999_999));
        jdbc.sql("""
                        insert into identity.users (id, first_name, last_name, display_name, email, phone, locale, status)
                        values (:id, 'Dana', 'Kowalski', 'Dana Kowalski', :email, :phone, 'en-CA', 'active')
                        """)
                .param("id", id)
                .param("email", email)
                .param("phone", phone)
                .update();
        return new Subject(id, email, phone, Locale.CANADA);
    }

    protected Erasure erase(Subject subject) {
        return inTransaction(() -> contributor().erase(subject));
    }

    protected <T> T inTransaction(Supplier<T> work) {
        var result = transactions.execute(_ -> work.get());
        assertThat(result).isNotNull();
        return result;
    }

    protected Optional<Section> section(Subject subject, String key) {
        return contributor().export(subject).stream()
                .filter(s -> s.key().equals(key))
                .findFirst();
    }

    protected int records(Subject subject, String key) {
        return section(subject, key).map(Section::records).orElse(0);
    }

    protected int count(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    protected @Nullable String text(String sql, Map<String, ?> params) {
        return jdbc.sql(sql)
                .params(params)
                .query((rs, _) -> Optional.ofNullable(rs.getString(1)))
                .single()
                .orElse(null);
    }

    /** Erasing again finds nothing more to do and reports the same. */
    protected void assertIdempotent(Subject subject, Erasure first) {
        var again = erase(subject);
        assertThat(again.retained()).isEqualTo(first.retained());
        assertThat(again.held()).isEqualTo(first.held());
    }
}
