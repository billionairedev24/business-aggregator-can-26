package ca.northline.merchants.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.shared.CodedEnum;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The domain enums mirror docs/spec (storefront-sections.json, legal-details.schema.json) and the V016 section trigger.
 * If the spec changes, these fail first.
 */
class SpecConsistencyTest {

    static JsonNode read(String resource) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                SpecConsistencyTest.class.getClassLoader().getResourceAsStream(resource))) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    static List<String> codes(List<SectionKind> kinds) {
        return kinds.stream().map(CodedEnum::code).toList();
    }

    @Test
    void pageKindsMatchStorefrontSectionsJson() throws IOException {
        var spec = read("spec/storefront-sections.json");
        assertThat(codes(PageKind.defaultOrder(MerchantType.PROVIDER)))
                .isEqualTo(strings(spec.path("page_kinds").path("business_page").path("default_order")));
        assertThat(codes(PageKind.defaultOrder(MerchantType.BOTH)))
                .isEqualTo(strings(spec.path("page_kinds").path("business_page").path("both_default_order")));
        assertThat(codes(PageKind.defaultOrder(MerchantType.SELLER)))
                .isEqualTo(strings(spec.path("page_kinds").path("store").path("default_order")));
        assertThat(codes(PageKind.defaultOrder(MerchantType.KITCHEN)))
                .isEqualTo(strings(spec.path("page_kinds").path("menu_page").path("default_order")));
        for (var kind : SectionKind.values()) {
            assertThat(spec.path("sections").path(kind.code()).path("required").asBoolean(false))
                    .as(kind.code())
                    .isEqualTo(kind.required());
        }
        assertThat(Arrays.stream(CtaLabel.values()).map(CodedEnum::code).toList())
                .isEqualTo(strings(spec.path("cta_labels")));
    }

    @Test
    void allowedSectionsMatchTheV016Trigger() throws IOException {
        String sql;
        try (InputStream in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("db/migration/V016__spec_constraints.sql"))) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (var kind : PageKind.values()) {
            var m = Pattern.compile("pk = '" + kind.code() + "' AND NEW.kind IN \\(([^)]*)\\)")
                    .matcher(sql);
            assertThat(m.find()).as(kind.code()).isTrue();
            var trigger = Arrays.stream(m.group(1).split(","))
                    .map(s -> s.strip().replace("'", ""))
                    .collect(Collectors.toSet());
            assertThat(kind.allowed().stream().map(CodedEnum::code).collect(Collectors.toSet()))
                    .as(kind.code())
                    .isEqualTo(trigger);
        }
    }

    @Test
    void principalRulesMatchLegalDetailsSchema() throws IOException {
        var defs = read("spec/legal-details.schema.json").path("$defs");
        for (var structure : BusinessStructure.values()) {
            var rules = defs.path(structure.code()).path("x-principals");
            assertThat(structure.minPrincipals())
                    .as(structure.code())
                    .isEqualTo(rules.path("min").asInt());
            assertThat(structure.kycThresholdPct())
                    .as(structure.code())
                    .isEqualTo(rules.path("kyc_threshold_pct").asInt());
            assertThat(structure.roles().stream().map(CodedEnum::code).collect(Collectors.toSet()))
                    .as(structure.code())
                    .isEqualTo(Set.copyOf(strings(rules.path("roles"))));
            var required = structure.requiredRole();
            var flag = required == null
                    ? null
                    : switch (required) {
                        case PARTNER_SIGNING -> "requires_signing";
                        case DIRECTOR -> "requires_director";
                        case CHAIR -> "requires_chair";
                        case PRESIDENT -> "requires_president";
                        default -> "unexpected";
                    };
            if (flag != null) {
                assertThat(rules.path(flag).asBoolean(false))
                        .as(structure.code() + " " + flag)
                        .isTrue();
            }
        }
    }
}
