package ca.northline.merchants.integration;

import ca.northline.merchants.application.BusinessRegistry;
import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistryRecord;
import ca.northline.merchants.domain.RegistrySource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code northline.registries.<source>.provider=fixtures} (the {@code local} and {@code test} default): answers from
 * {@code classpath:registries/fixtures.json}. Unknown numbers are "not found" (→ manual review), numbers containing
 * {@code offline} are "unavailable". Refused under staging/prod.
 */
class FixtureBusinessRegistry implements BusinessRegistry {

    private static final JsonNode FIXTURES = load();

    private final RegistrySource source;

    FixtureBusinessRegistry(RegistrySource source) {
        this.source = source;
    }

    @Override
    public RegistrySource source() {
        return source;
    }

    @Override
    public Answer lookup(RegistryQuery query) {
        if (query.number().toLowerCase(Locale.ROOT).contains("offline")) {
            return new Answer.Unavailable("fixture: offline");
        }
        var row = FIXTURES.path(source.code()).path(query.number());
        if (row.isMissingNode() || row.isNull()) {
            return new Answer.NotFound("fixtures:" + source.code());
        }
        var status = row.path("status").asString();
        var expires = row.path("expiresOn");
        return new Answer.Found(
                new RegistryRecord(
                        row.path("name").asString(),
                        query.number(),
                        RegistryStandings.of(status),
                        status,
                        expires.isMissingNode() ? null : LocalDate.parse(expires.asString())),
                "fixtures:" + source.code() + "/" + query.number());
    }

    private static JsonNode load() {
        try (var in = new ClassPathResource("registries/fixtures.json").getInputStream()) {
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
