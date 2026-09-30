package ca.northline.merchants.integration;

import ca.northline.merchants.application.BusinessRegistry;
import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistryRecord;
import ca.northline.merchants.domain.RegistrySource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alberta Corporate Registry through a search service: OpenCorporates' company API for the {@code ca_ab} jurisdiction
 * (data taken from Service Alberta's Corporate Registry; {@code GET {base}/v0.4/companies/ca_ab/{number}?api_token=}).
 * Alberta has no public registry API — the legal source of record is a registry-agent search — so a match here
 * verifies, and anything else (not found, a different name, not active, the service down) goes to an agent who runs
 * the registry-agent search (docs/runbooks/registries.md).
 */
@Slf4j
class OpenCorporatesAlbertaRegistry implements BusinessRegistry {

    interface Api {
        @GetExchange("/v0.4/companies/ca_ab/{number}")
        String company(@PathVariable String number, @RequestParam("api_token") String token);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Api api;
    private final String token;

    OpenCorporatesAlbertaRegistry(Api api, String token) {
        this.api = api;
        this.token = token;
    }

    @Override
    public RegistrySource source() {
        return RegistrySource.ALBERTA_CORPORATE_REGISTRY;
    }

    @Override
    public Answer lookup(RegistryQuery query) {
        var number = query.number().replaceAll("[^A-Za-z0-9-]", "");
        try {
            return parse(api.company(number, token), number);
        } catch (HttpClientErrorException.NotFound _) {
            return new Answer.NotFound("opencorporates:ca_ab/" + number);
        } catch (RestClientException e) {
            log.warn("OpenCorporates lookup of Alberta {} failed: {}", number, e.getMessage());
            return new Answer.Unavailable(
                    "alberta_corporate_registry: " + e.getClass().getSimpleName());
        }
    }

    static Answer parse(String body, String number) {
        var company = JSON.readTree(body).path("results").path("company");
        var name = company.path("name").asString("");
        if (company.isMissingNode() || name.isBlank()) {
            return new Answer.NotFound("opencorporates:ca_ab/" + number);
        }
        var status = company.path("current_status").asString(null);
        var standing = company.path("inactive").asBoolean(false)
                ? RegistryRecord.Standing.INACTIVE
                : RegistryStandings.of(status);
        var url = company.path("opencorporates_url").asString(null);
        return new Answer.Found(
                new RegistryRecord(name, company.path("company_number").asString(number), standing, status, null),
                url == null ? "opencorporates:ca_ab/" + number : url);
    }
}
