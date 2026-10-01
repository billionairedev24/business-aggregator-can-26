import * as Crypto from 'expo-crypto';

import { base64url } from '../bytes';

/** Cryptographically secure random bytes from the platform (SecRandomCopyBytes / SecureRandom / crypto.getRandomValues). */
export function randomBytes(n: number): Uint8Array {
  return Crypto.getRandomBytes(n);
}

/** A random id: a DPoP proof's `jti`, an action's Idempotency-Key. */
export function randomId(): string {
  return Crypto.randomUUID();
}

/** An opaque random string of `bytes` bytes, base64url (OAuth `state`, PKCE verifier). */
export function randomToken(bytes = 32): string {
  return base64url(randomBytes(bytes));
}
