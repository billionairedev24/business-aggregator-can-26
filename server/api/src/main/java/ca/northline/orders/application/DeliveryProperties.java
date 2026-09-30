package ca.northline.orders.application;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.orders.delivery}: the markets Northline delivers goods in and the pooled runs each gets every day
 * (design 06: "Tonight 6–9 pm · Pooled with 5 neighbours · $2.99", "Tomorrow 8–11 am · $1.99", "Now · 45 min · Direct
 * courier · $9.99"). Times are local to {@code America/Edmonton}.
 *
 * @param markets cities with pooled delivery (design 06 Location: Calgary, Edmonton, Airdrie)
 * @param orderLead how long before a run's pack-by time customers must order (the shop's packing time)
 */
@ConfigurationProperties("northline.orders.delivery")
public record DeliveryProperties(
        @DefaultValue({"Calgary", "Edmonton", "Airdrie"}) List<String> markets,
        @DefaultValue("25m") Duration orderLead,
        @DefaultValue List<RunSlot> runs,
        @DefaultValue Direct direct) {

    public DeliveryProperties {
        markets = List.copyOf(markets);
        runs = runs.isEmpty() ? List.of(RunSlot.EVENING, RunSlot.MORNING) : List.copyOf(runs);
    }

    /**
     * A daily run. {@code cutoff} is when shops must have packed (the window's {@code cutoff_at}); a cut-off later than
     * the start belongs to the day before (an early-morning run packed the evening before).
     */
    public record RunSlot(
            String name, LocalTime starts, LocalTime ends, LocalTime cutoff, long feeCents, int capacity) {

        static final RunSlot EVENING =
                new RunSlot("evening", LocalTime.of(18, 0), LocalTime.of(21, 0), LocalTime.of(17, 45), 299, 40);
        static final RunSlot MORNING =
                new RunSlot("morning", LocalTime.of(8, 0), LocalTime.of(11, 0), LocalTime.of(7, 30), 199, 40);
    }

    /** The direct courier: no run, arrives in about {@code eta}. */
    public record Direct(
            @DefaultValue("45m") Duration eta,
            @DefaultValue("999") long feeCents) {}
}
