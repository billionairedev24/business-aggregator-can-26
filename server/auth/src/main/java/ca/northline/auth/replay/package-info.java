/**
 * One-time ids and shared per-window values for the whole auth server (S-29, S-30): DPoP proof ids and nonces, and
 * the {@code jti} of partners' client assertions. Valkey in the cloud (every instance sees every id), memory for local
 * runs and tests — {@code northline.auth.replay.store}.
 */
@NullMarked
package ca.northline.auth.replay;

import org.jspecify.annotations.NullMarked;
