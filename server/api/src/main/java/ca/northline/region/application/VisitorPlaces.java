package ca.northline.region.application;

import ca.northline.region.application.GeoUseCases.LocateVisitor;
import ca.northline.region.application.GeoUseCases.LookUpPostalCode;
import ca.northline.region.application.GeoUseCases.NamePlace;
import ca.northline.region.application.GeoViews.PostalPlace;
import ca.northline.region.domain.GeoPoint;
import ca.northline.shared.PlaceParams;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * S-192 (Amazon-style location chooser): the visitor's guessed place ({@link VisitorLocator}) and a postal or ZIP code
 * of any country ({@link PlacesAutocomplete#postalCode}), resolved to a market by the same PostGIS lookup as an
 * address ({@link NamePlace#resolve}). Neither ever requires anything (S-190): an unknown code is kept as typed.
 */
@Service
@RequiredArgsConstructor
class VisitorPlaces implements LocateVisitor, LookUpPostalCode {

    private final VisitorLocator locator;
    private final PlacesAutocomplete places;
    private final NamePlace names;

    @Override
    public Optional<VisitorLocator.VisitorPlace> locate(VisitorLocator.VisitorRequest request) {
        return locator.locate(request).filter(p -> !p.empty());
    }

    @Override
    public PostalPlace lookUp(@Nullable String code, @Nullable String country, Locale locale) {
        var postal = PlaceParams.postalCode("code", code);
        var cc = PlaceParams.country("country", country);
        var found = places.postalCode(postal, cc, locale);
        if (found.isEmpty()) {
            return new PostalPlace(postal, false, null, null, cc, null, null, null);
        }
        var p = found.get();
        GeoPoint point = p.point();
        return new PostalPlace(
                p.postalCode() != null ? p.postalCode() : postal,
                true,
                p.city(),
                p.province(),
                p.country() != null ? p.country() : cc,
                point.lat(),
                point.lng(),
                names.resolve(point));
    }
}
