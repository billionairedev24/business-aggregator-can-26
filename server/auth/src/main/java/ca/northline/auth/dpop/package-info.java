/**
 * DPoP (RFC 9449) for the mobile and courier apps (S-29), on top of Spring Authorization Server's own DPoP support
 * (proof verification in the grant providers, {@code cnf.jkt} binding, the public-client refresh key check): proofs
 * required for {@code dpop-required} clients, server nonces ({@code DPoP-Nonce}), single-use proof ids in Valkey, the
 * refresh-token grant for public clients (authenticated by their DPoP key), and refresh tokens for them at all.
 */
@NullMarked
package ca.northline.auth.dpop;

import org.jspecify.annotations.NullMarked;
