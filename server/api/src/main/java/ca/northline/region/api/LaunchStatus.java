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
}
