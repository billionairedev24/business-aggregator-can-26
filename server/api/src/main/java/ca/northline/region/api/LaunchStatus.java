package ca.northline.region.api;

import ca.northline.shared.CodedEnum;

/** Launch status of a province or market ({@code region.regions.stage}). Only {@code live} places take orders. */
public enum LaunchStatus implements CodedEnum {
    OFF,
    WAITLIST,
    PILOT,
    LIVE;

    public boolean live() {
        return this == LIVE;
    }

    /** More open than {@code other} (off &lt; waitlist &lt; pilot &lt; live): a market can't be above its province. */
    public boolean above(LaunchStatus other) {
        return compareTo(other) > 0;
    }
}
