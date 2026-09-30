package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** A point of sale a kitchen imports its menu from (S-36): {@code food.pos_connections.provider}. */
public enum PosProvider implements CodedEnum {
    SQUARE,
    CLOVER,
    TOAST;

    /** Toast is partner-gated: no merchant OAuth, the kitchen gives its restaurant GUID after enabling Northline. */
    public boolean oauth() {
        return this != TOAST;
    }
}
