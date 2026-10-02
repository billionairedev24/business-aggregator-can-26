package ca.northline.privacy.domain;

import ca.northline.shared.CodedEnum;

/**
 * How the person proved it is them: a fresh step-up (passkey or authenticator, northline-auth's proof), a code texted
 * to the account's verified mobile, or staff (a request made by email or mail, checked by hand).
 */
public enum Verification implements CodedEnum {
    STEP_UP,
    CODE,
    STAFF
}
