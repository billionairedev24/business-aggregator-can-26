package ca.northline.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.region.api.AgeClass;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Age-restricted listings always reach a person; restricted words outside a restricted category too. */
class AgeRestrictedVettingTest {

    static CategoryProfile category(String id, @Nullable AgeClass ageClass) {
        return new CategoryProfile(
                id, null, "shop", id, true, null, false, false, List.of(), List.of(), null, ageClass);
    }

    static List<VettingFlag> vet(CategoryProfile c, boolean licenceOk, String text) {
        return AutomatedVetting.check(
                new AutomatedVetting.Subject(ListingKind.PRODUCT, c, 2500L, licenceOk, false, true, null, text));
    }

    @Test
    void aRestrictedCategoryNeedsTheLicenceAndAPerson() {
        var wine = category("shop.restricted.alcohol", AgeClass.ALCOHOL);
        assertThat(vet(wine, false, "Pinot noir"))
                .containsExactly(VettingFlag.MISSING_LICENCE, VettingFlag.AGE_RESTRICTED);
        assertThat(vet(wine, true, "Pinot noir")).containsExactly(VettingFlag.AGE_RESTRICTED);
    }

    @Test
    void wineListedAsGroceriesIsCaught() {
        var groceries = category("shop.food-and-grocery.groceries", null);
        assertThat(vet(groceries, true, "Red wine, 750 ml")).containsExactly(VettingFlag.AGE_CLASS_MISMATCH);
        assertThat(vet(groceries, true, "Bière blonde 6 x 355 ml")).containsExactly(VettingFlag.AGE_CLASS_MISMATCH);
        assertThat(vet(groceries, true, "Disposable vape, mint")).containsExactly(VettingFlag.AGE_CLASS_MISMATCH);
        assertThat(vet(groceries, true, "Sourdough loaf")).isEmpty();
        assertThat(vet(groceries, true, "Ginger snaps")).isEmpty(); // whole words only
    }

    @Test
    void theWordsAreFoundInEnglishAndFrench() {
        assertThat(AgeRestrictedWords.find("Coffret de VINS du Québec")).isEqualTo("vins");
        assertThat(AgeRestrictedWords.find("E-liquid 3 mg nicotine")).isEqualTo("e-liquid");
        assertThat(AgeRestrictedWords.find("Winery tour gift card")).isNull();
    }
}
