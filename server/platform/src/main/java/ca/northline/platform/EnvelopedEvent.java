package ca.northline.platform;

/**
 * An event that travels on Kafka with the Northline envelope headers ({@link EventHeaders}): the api's
 * {@code DomainEvent}s and northline-auth's events. Only what the headers need that the payload's JSON doesn't say.
 */
public interface EnvelopedEvent {

    /** ULID, the consumers' dedupe key ({@code nl-event-id}, equal to the payload's {@code eventId}). */
    String eventId();

    /** Schema version ({@code nl-event-version}); a breaking payload change is a new version. */
    default int version() {
        return 1;
    }
}
