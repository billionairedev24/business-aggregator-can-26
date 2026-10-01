/**
 * Region: provinces, the city markets inside them and their delivery zones (PostGIS), tax profiles, and — since S-47 —
 * addresses: Google Places autocomplete and reverse geocoding behind the {@code PlacesAutocomplete} port, resolved to a
 * market and zone, and the waitlist for places Northline doesn't serve yet.
 */
@org.springframework.modulith.ApplicationModule(displayName = "region")
@org.jspecify.annotations.NullMarked
package ca.northline.region;
