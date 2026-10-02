package ca.northline.auth.application;

/**
 * Outbound port (S-105): forgets what northline-auth itself keeps about accounts the api has erased ({@code
 * identity.users.status = 'erased'}): their passkeys (WebAuthn credentials), authenticator secret, backup codes, linked
 * Google / Apple identities, consents and OAuth authorizations (refresh tokens). Idempotent.
 */
public interface ErasedAccounts {

    /** Purges up to {@code limit} erased accounts that still have auth rows; returns how many accounts. */
    int purge(int limit);
}
