package ca.northline.region.domain;

import ca.northline.shared.CodedEnum;

/** Rollout stage of a province or market ({@code region.regions.stage}). Only {@code live} places take orders. */
public enum Stage implements CodedEnum {
    OFF,
    WAITLIST,
    PILOT,
    LIVE;

    public boolean live() {
        return this == LIVE;
    }
}
