package ca.northline.region.persistence;

import ca.northline.region.api.LegalForms;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link LegalForms} over {@code region.legal_forms} (V372). Read on each call: the Business step asks once per save. */
@Repository
@RequiredArgsConstructor
class LegalFormQueries implements LegalForms {

    private static final String SELECT = """
            select province, structure, registry_i18n::text as registry, registry_url, law_i18n::text as law,
                   trade_name_registration, fields::text as fields, source, verified, unverified
              from region.legal_forms
            """;
    private static final String ORDER =
            " order by array_position(array['sole','partnership','corp_prov','corp_fed','corp_ex','coop','nonprofit'],"
                    + " structure)";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public Optional<LegalForm> form(@Nullable String province, String structure) {
        var code = code(province);
        if (code == null) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " where province = :p and structure = :s")
                .param("p", code)
                .param("s", structure)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public List<LegalForm> forms(@Nullable String province) {
        var code = code(province);
        if (code == null) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where province = :p" + ORDER)
                .param("p", code)
                .query((rs, _) -> map(rs))
                .list();
    }

    private static @Nullable String code(@Nullable String province) {
        return province == null || province.isBlank() ? null : province.strip().toUpperCase(Locale.ROOT);
    }

    private static LegalForm map(ResultSet rs) throws SQLException {
        var fields = new LinkedHashMap<String, FieldRule>();
        JSON.readTree(rs.getString("fields")).properties().forEach(e -> fields.put(e.getKey(), rule(e.getValue())));
        var unverified = rs.getArray("unverified");
        return new LegalForm(
                rs.getString("province"),
                rs.getString("structure"),
                text(JSON.readTree(rs.getString("registry"))),
                rs.getString("registry_url"),
                text(JSON.readTree(rs.getString("law"))),
                "required".equals(rs.getString("trade_name_registration")),
                fields,
                rs.getString("source"),
                rs.getBoolean("verified"),
                unverified == null ? List.of() : Arrays.asList((String[]) unverified.getArray()));
    }

    static FieldRule rule(JsonNode n) {
        var required = n.path("required");
        var when = n.path("required_when");
        return new FieldRule(
                optionalText(n.path("label")),
                optionalText(n.path("help")),
                string(n.path("example")),
                string(n.path("pattern")),
                optionalText(n.path("pattern_message")),
                required.isBoolean() ? required.asBoolean() : null,
                when.isObject()
                        ? new Condition(
                                when.path("field").asString(), strings(when.path("in")), strings(when.path("not_in")))
                        : null);
    }

    private static Text text(JsonNode n) {
        var en = n.path("en").asString("");
        return new Text(en, n.path("fr").asString(en));
    }

    private static @Nullable Text optionalText(JsonNode n) {
        return n.isObject() ? text(n) : null;
    }

    private static @Nullable String string(JsonNode n) {
        return n.isString() && !n.asString().isBlank() ? n.asString() : null;
    }

    private static List<String> strings(JsonNode n) {
        var out = new ArrayList<String>();
        n.forEach(v -> out.add(v.asString()));
        return out;
    }
}
