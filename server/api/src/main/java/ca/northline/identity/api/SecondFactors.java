package ca.northline.identity.api;

/**
 * Whether a person has a second factor (a passkey or an authenticator app) — the S-51 payment rule: a sign-in without
 * one (a phone code) steps up with it before paying, or enrols one first when there is none.
 */
public interface SecondFactors {

    boolean hasSecondFactor(String userId);
}
