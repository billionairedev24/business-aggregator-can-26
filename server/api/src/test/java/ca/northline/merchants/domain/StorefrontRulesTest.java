package ca.northline.merchants.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StorefrontRulesTest {

    static final Instant NOW = Instant.parse("2026-09-29T16:00:00Z");

    /** Design 02 swatches (computed WCAG ratios; see DECISIONS.md) and a failing honey. */
    @ParameterizedTest
    @CsvSource({"#2f5d3a, 7.6", "#9a4a1f, 6.2", "#5b2a5e, 10.8", "#15231b, 16.3", "#006786, 6.4", "#3b4a5a, 9.1"})
    void curatedSwatchesPassAA(String hex, double ratio) {
        assertThat(Math.round(BrandColor.contrastWithWhite(hex) * 10) / 10.0).isEqualTo(ratio);
        assertThat(new BrandColor(hex).hex()).isEqualTo(hex);
    }

    @Test
    void lightColoursFail() {
        assertThatThrownBy(() -> new BrandColor("#D9A441"))
                .isInstanceOf(RuleViolation.class)
                .hasMessage(BrandColor.CONTRAST);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Prairie Wrench        | prairie-wrench",
                "Pho Đậu Bò            | pho-dau-bo",
                "Glenmore Bakery & Co. | glenmore-bakery-and-co",
                "A                     | a-nl",
                "!!                    | biz",
            })
    void slugsFromNames(String name, String slug) {
        assertThat(Slug.from(name).value()).isEqualTo(slug);
    }

    @Test
    void longSlugsAreCutAndNumbered() {
        var slug = Slug.from("x".repeat(60));
        assertThat(slug.value()).hasSize(40);
        assertThat(slug.numbered(12).value()).hasSize(40).endsWith("-12");
    }

    @Test
    void publishNeedsApprovalAndAVerifiedDomain() {
        var page = Storefront.create("M", MerchantType.PROVIDER, new Slug("aspen"), NOW);
        assertThatThrownBy(() -> page.publish("U", false, NOW)).isInstanceOf(Conflict.class);
        page.connectDomain(new CustomDomain("Book.Aspen.ca"), NOW);
        assertThat(page.getCustomDomain()).isEqualTo(new CustomDomain("book.aspen.ca"));
        assertThatThrownBy(() -> page.publish("U", true, NOW)).isInstanceOf(RuleViolation.class);
        page.domainChecked(CustomDomain.Status.VERIFIED, NOW);
        assertThat(page.publish("U", true, NOW).customDomain()).isEqualTo("book.aspen.ca");
    }

    @Test
    void arrangeKeepsTheLibrary() {
        var page = Storefront.create("M", MerchantType.SELLER, new Slug("shop"), NOW);
        var reversed = page.sections().reversed().stream()
                .map(s -> new Storefront.SectionState(s.getKind(), true, null))
                .toList();
        page.arrange(reversed, NOW);
        assertThat(page.sections().getFirst().getKind()).isEqualTo(SectionKind.CTA);
        assertThatThrownBy(() -> page.arrange(List.of(), NOW)).isInstanceOf(RuleViolation.class);
    }

    @Test
    void checklistFollowsTypeAndRegulators() {
        var amvic = new SelectedCategory(
                "service.automotive.mobile-mechanic", "Mobile mechanic", CategoryRoot.SERVICE, "AMVIC", false);
        var plumber = new SelectedCategory(
                "service.home-trades.plumber", "Plumber", CategoryRoot.SERVICE, "Safety Codes", false);
        var alcohol = new SelectedCategory("shop.restricted.alcohol", "Alcohol", CategoryRoot.SHOP, "AGLC", false);
        assertThat(CheckSpec.checklist(MerchantType.PROVIDER, List.of(amvic, plumber)).stream()
                        .map(CheckSpec::key))
                .containsExactly(
                        "kyc", "registry", "licence:AMVIC", "licence:Safety Codes", "insurance", "bank", "mfa");
        assertThat(CheckSpec.checklist(MerchantType.BOTH, List.of(amvic, alcohol)).stream()
                        .map(CheckSpec::key))
                .containsExactly(
                        "kyc",
                        "registry",
                        "licence:AMVIC",
                        "insurance",
                        "category_permits",
                        "product_safety",
                        "bank",
                        "mfa");
        var truck =
                new SelectedCategory("food.format.food-truck", "Food truck", CategoryRoot.FOOD, "Mobile permit", false);
        assertThat(CheckSpec.checklist(MerchantType.KITCHEN, List.of(truck)).stream()
                        .map(CheckSpec::key))
                .contains("licence:Mobile permit")
                .startsWith("kyc", "registry", "ahs_permit", "licence:Mobile permit");
    }

    @Test
    void gstIsCanonicalised() {
        assertThat(new GstNumber("123456789rt0001").value()).isEqualTo("123456789 RT0001");
        assertThatThrownBy(() -> new GstNumber("123 RT0001")).hasMessage(GstNumber.FORMAT);
    }
}
