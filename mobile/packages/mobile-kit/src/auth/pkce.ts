import { sha256 } from '@noble/hashes/sha2.js';

import { base64url, utf8 } from '../bytes';
import { randomToken } from '../dpop/random';

/** RFC 7636: a 43-character verifier (32 random bytes, base64url) and its S256 challenge. */
export function codeVerifier(): string {
  return randomToken(32);
}

export function codeChallenge(verifier: string): string {
  return base64url(sha256(utf8(verifier)));
}
