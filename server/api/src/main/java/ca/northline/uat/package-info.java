/**
 * UAT with the pilot group (S-121): the pilot participants (people and businesses), the feedback they send from the
 * product, staff's triage of it, participants' sign-offs of the UAT scripts in {@code docs/uat/}, and the go/no-go
 * report S-118's go-live checklist reads ({@link ca.northline.uat.api.UatReadiness}). Owns schema {@code uat}. Runbook:
 * docs/uat/README.md.
 */
@ApplicationModule(displayName = "uat")
@NullMarked
package ca.northline.uat;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
