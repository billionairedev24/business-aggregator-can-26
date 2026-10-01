package ca.northline.shared;

import java.util.Optional;

/**
 * French place names for messages built in English (S-40): the 422 handler translates "Northline isn't open in Nova
 * Scotia yet." and needs "Nouvelle-Écosse" — or, with the preposition, "en Nouvelle-Écosse". Implemented by the region
 * module from its province profiles; shared code never names a place itself.
 */
public interface PlaceNames {

    /** The French of an English place name ({@code in}: with its preposition); empty when the place is unknown. */
    Optional<String> french(String englishName, boolean in);
}
