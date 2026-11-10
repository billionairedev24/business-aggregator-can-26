package ca.northline.region.application;

import ca.northline.region.application.GeoViews.Address;
import ca.northline.region.application.GeoViews.Place;
import ca.northline.region.application.GeoViews.Resolution;
import ca.northline.region.application.GeoViews.Suggestions;
import ca.northline.region.domain.GeoPoint;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Inbound ports of the Location screen and the pill (S-47). */
public final class GeoUseCases {
    private GeoUseCases() {}

    public interface SuggestAddresses {
        int MIN_INPUT = 3;
        int MAX_INPUT = 200;

        Suggestions suggest(String input, @Nullable String sessionToken, Locale locale, @Nullable GeoPoint near);
    }

    public interface ChooseAddress {
        Address address(String placeId, @Nullable String sessionToken, Locale locale);
    }

    public interface NamePlace {
        Optional<Place> name(GeoPoint point, Locale locale);

        Resolution resolve(GeoPoint point);
    }

    /** S-192: the visitor's place guessed from the request (the configured {@link VisitorLocator}); empty = no guess. */
    public interface LocateVisitor {
        Optional<VisitorLocator.VisitorPlace> locate(VisitorLocator.VisitorRequest request);
    }

    /**
     * S-192: a postal or ZIP code of any country (the location dialog's "Apply"), checked with
     * {@code PlaceParams.postalCode} / {@code country} (422), then looked up where the places provider knows it.
     */
    public interface LookUpPostalCode {
        GeoViews.PostalPlace lookUp(@Nullable String code, @Nullable String country, Locale locale);
    }

    public interface BrowseMarkets {
        GeoViews.Markets provinces(Locale locale);
    }

    public interface JoinWaitlist {
        /** @return true when added, false when already on the list */
        boolean join(Command command);

        /** @param userId the signed-in person, or null for a guest (then {@code email} is required) */
        record Command(
                String regionId,
                @Nullable String userId,
                @Nullable String email,
                Locale locale) {}
    }
}
