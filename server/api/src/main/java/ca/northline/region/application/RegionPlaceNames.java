package ca.northline.region.application;

import ca.northline.region.api.Regions;
import ca.northline.shared.PlaceNames;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link PlaceNames} from the province profiles: "Nova Scotia" → "Nouvelle-Écosse" / "en Nouvelle-Écosse" (S-40). */
@Component
@RequiredArgsConstructor
class RegionPlaceNames implements PlaceNames {

    private final Regions regions;

    @Override
    public Optional<String> french(String englishName, boolean in) {
        return regions.provinces().stream()
                .filter(p -> p.nameEn().equalsIgnoreCase(englishName.strip()))
                .findFirst()
                .map(p -> in ? p.nameIn(Locale.CANADA_FRENCH) : p.name(Locale.CANADA_FRENCH));
    }
}
