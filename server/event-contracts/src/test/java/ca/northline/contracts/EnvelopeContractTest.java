package ca.northline.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.auth.application.UserRegistered;
import ca.northline.platform.EventHeaders;
import ca.northline.worker.events.EnvelopeParser;
import ca.northline.worker.events.EventSchemas;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Producers ↔ the worker's consumer framework (S-26): what the api's and northline-auth's externalization puts on
 * Kafka (topic, key, {@code nl-event-*} headers from the shared {@link EventHeaders#externalization}, the record's
 * JSON) goes through the worker's {@link EnvelopeParser} and validates against its schema — no producer's events are
 * dead-lettered as poison for a missing header.
 */
class EnvelopeContractTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final EnvelopeParser PARSER = new EnvelopeParser(JSON, EventSchemas.fromClasspath(JSON));

    @Test
    void authsUserRegistered_parsesThroughTheWorkersEnvelopeParser() {
        // northline-auth's configuration (auth config.EventExternalizationConfig) is exactly this call.
        var auth = EventHeaders.externalization("ca.northline.auth");
        var event = new UserRegistered(
                "01J9ZD3V00000000000000EVT1", Instant.parse("2026-09-30T18:00:00Z"), "01J9ZD3V00000000000000RAV1");

        assertThat(auth.supports(event)).isTrue();
        var target = auth.determineTarget(event);
        assertThat(target.getTarget()).isEqualTo("identity.user");
        // The externalizer evaluates the key expression on send (UserRegisteredEventsTest checks the real key).
        assertThat(target.getKey()).isEqualTo("#{aggregateId()}");
        assertThat(auth.getHeadersFor(event))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        EventHeaders.ID, "01J9ZD3V00000000000000EVT1",
                        EventHeaders.TYPE, "identity.user_registered",
                        EventHeaders.VERSION, "1"));

        var envelope = PARSER.parse(record(auth, event));

        assertThat(envelope.id()).isEqualTo("01J9ZD3V00000000000000EVT1");
        assertThat(envelope.type()).isEqualTo("identity.user_registered");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-09-30T18:00:00Z"));
        assertThat(envelope.aggregateId()).isEqualTo("01J9ZD3V00000000000000RAV1");
        assertThat(envelope.topic()).isEqualTo("identity.user");
    }

    @Test
    void everyExternalizedEvent_ofEveryProducer_parsesThroughTheWorkersEnvelopeParser() {
        var producers = EventHeaders.externalization("ca.northline"); // the api's; covers ca.northline.auth too
        var schemas = SchemaFiles.read(Path.of(System.getProperty("northline.repo", "../..")), JSON);
        var failures = new ArrayList<String>();
        var events = PublishedEvents.scan();
        for (var published : events) {
            var schema = schemas.get(published.schemaFile());
            if (schema == null) {
                continue; // check 2 of EventContractsTest reports it
            }
            var event = SamplePayloads.build(published.type(), schema.schema(), SamplePayloads.Variant.FULL);
            try {
                PARSER.parse(record(producers, event));
            } catch (RuntimeException e) {
                failures.add(published.name() + ": " + e.getMessage());
            }
        }
        assertThat(events).extracting(PublishedEvents.Event::type).contains(UserRegistered.class);
        assertThat(failures).isEmpty();
    }

    /**
     * The Kafka record the externalizer produces for {@code event}, as the worker's consumers receive it (the key is
     * left as the routing expression: the parser doesn't read it).
     */
    private static ConsumerRecord<String, byte[]> record(EventExternalizationConfiguration config, Object event) {
        var target = config.determineTarget(event);
        var headers = new RecordHeaders();
        config.getHeadersFor(event)
                .forEach((name, value) -> headers.add(name, value.toString().getBytes(StandardCharsets.UTF_8)));
        return new ConsumerRecord<>(
                target.getTarget(),
                0,
                0L,
                0L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                0,
                0,
                target.getKey(),
                JSON.writeValueAsBytes(config.map(event)),
                headers,
                java.util.Optional.empty());
    }
}
