package ca.northline.hire.application;

/** S-153: the balance job's entry point (the scheduler in {@code hire.infra}; tests call it directly). */
public interface BalanceHolds {

    /** Holds every due balance it can; returns how many were held. */
    int holdDue();
}
