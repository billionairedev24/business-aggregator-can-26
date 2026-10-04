package ca.northline.shared.security;

import ca.northline.shared.CodedEnum;
import java.util.Set;

/**
 * The platform console's screens (design 03, docs/CONSOLE_PLAN.md § Routes). A {@link StaffRole} opens a set of them;
 * {@link #PROFILE} and {@link #ONCALL} are open to every staff member whatever their roles.
 */
public enum ConsoleScreen implements CodedEnum {
    OVERVIEW,
    ORDERS,
    DISPUTES,
    DELIVERY,
    SELLERS,
    VERIFY,
    VETTING,
    TRUST,
    TAXONOMY,
    SUPPORT,
    REGIONS,
    FINANCE,
    REPORTS,
    API,
    TEAM,
    /** S-105: people's privacy requests (access, correction, erasure). */
    PRIVACY,
    /** S-120: pilot merchant onboarding — the pipeline per market, invites, kitchen visits. */
    PILOT,
    /** S-121: UAT with the pilot group — feedback triage, participants' sign-offs, the go/no-go report. */
    UAT,
    /** S-118: a market's go-live checklist, the two-person switch to live, rollback and hypercare. */
    GO_LIVE,
    PROFILE,
    ONCALL;

    private static final Set<ConsoleScreen> EVERYONE = Set.of(PROFILE, ONCALL);

    /** Open to any staff member, with or without a console role (design: {@code canSee} profile / oncall). */
    public boolean openToAllStaff() {
        return EVERYONE.contains(this);
    }
}
