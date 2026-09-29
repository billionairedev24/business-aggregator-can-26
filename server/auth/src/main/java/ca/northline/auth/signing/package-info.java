/**
 * Token signing keys (backlog S-7). {@link ca.northline.auth.signing.SigningKeys} is the port: the key that signs now
 * and every public key the JWK set publishes. Adapters are chosen by {@code northline.kms.provider}: a JWK set file for
 * {@code local} ({@link ca.northline.auth.signing.LocalFileSigningKeys}), or a cloud key service that signs remotely so
 * the private key never leaves it (AWS KMS, Google Cloud KMS, Azure Key Vault). {@link
 * ca.northline.auth.signing.KeyStoreJwtEncoder} is the {@code JwtEncoder} Spring Authorization Server and the step-up
 * proofs sign with.
 */
@NullMarked
package ca.northline.auth.signing;

import org.jspecify.annotations.NullMarked;
