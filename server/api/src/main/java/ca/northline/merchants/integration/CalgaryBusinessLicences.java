package ca.northline.merchants.integration;

import ca.northline.merchants.application.BusinessRegistry;
import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistryRecord;
import ca.northline.merchants.domain.RegistrySource;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * City of Calgary business licences — Open Calgary's Socrata dataset "Calgary Business Licences" ({@code vdjc-pybd}),
 * SODA API {@code GET {base}/resource/{dataset}.json?getbusid=…} with an optional {@code X-App-Token} (without one,
 * Socrata throttles per IP). Columns used: {@code getbusid}, {@code tradename}, {@code licencetypes},
 * {@code jobstatusdesc}, {@code exp_dt}. Several rows for one licence (renewals) → the latest expiry wins. A number
 * typed with a {@code BL} prefix is also tried without it.
 */
@Slf4j
class CalgaryBusinessLicences implements BusinessRegistry {

    interface Api {
        @GetExchange("/resource/{dataset}.json")
        String rows(@PathVariable String dataset, @RequestParam("getbusid") String id);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Api api;
    private final String dataset;
    private final String publicUrl;

    CalgaryBusinessLicences(Api api, String dataset, String publicUrl) {
        this.api = api;
        this.dataset = dataset;
        this.publicUrl = publicUrl;
    }

    @Override
    public RegistrySource source() {
        return RegistrySource.CALGARY_BUSINESS_LICENCES;
    }

    @Override
    public Answer lookup(RegistryQuery query) {
        var number = query.number().strip();
        try {
            var answer = parse(api.rows(dataset, number), number, reference(number));
            var bare = number.replaceFirst("(?i)^BL\\s*", "");
            if (answer instanceof Answer.NotFound && !bare.equals(number)) {
                answer = parse(api.rows(dataset, bare), bare, reference(bare));
            }
            return answer;
        } catch (RestClientException e) {
            log.warn("Calgary business licence lookup of {} failed: {}", number, e.getMessage());
            return new Answer.Unavailable(
                    "calgary_business_licences: " + e.getClass().getSimpleName());
        }
    }

    private String reference(String number) {
        return publicUrl + "/resource/" + dataset + ".json?getbusid=" + number;
    }

    static Answer parse(@Nullable String body, String number, String reference) {
        if (body == null || body.isBlank()) {
            return new Answer.NotFound(reference);
        }
        var rows = JSON.readTree(body);
        var latest = StreamSupport.stream(rows.spliterator(), false)
                .max(Comparator.comparing((JsonNode r) -> expiry(r), Comparator.nullsFirst(Comparator.naturalOrder())));
        if (latest.isEmpty()) {
            return new Answer.NotFound(reference);
        }
        var row = latest.get();
        var status = row.path("jobstatusdesc").asString(null);
        return new Answer.Found(
                new RegistryRecord(
                        row.path("tradename").asString(""),
                        row.path("getbusid").asString(number),
                        RegistryStandings.of(status),
                        status,
                        expiry(row)),
                reference);
    }

    /** Socrata floating timestamps ({@code 2027-01-12T00:00:00.000}) or plain dates. */
    static @Nullable LocalDate expiry(JsonNode row) {
        var raw = row.path("exp_dt").asString("");
        return raw.length() < 10 ? null : LocalDate.parse(raw.substring(0, 10).replace('/', '-'));
    }
}
