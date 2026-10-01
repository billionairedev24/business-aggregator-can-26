import { sha256 } from '@noble/hashes/sha2.js';

import { base64url, utf8 } from '../bytes';
import type { DeviceKey } from './key';
import { randomId } from './random';

/**
 * Server time for proofs' `iat` (northline-auth and the api allow 30 s; phones drift). Learns the offset from the
 * `Date` header of every answer (docs/runbooks/mobile-auth.md § 3).
 */
export class ServerClock {
  private offsetMs = 0;

  constructor(private readonly now: () => number = Date.now) {}

  /** Epoch seconds on the server's clock. */
  seconds(): number {
    return Math.floor((this.now() + this.offsetMs) / 1000);
  }

  observe(dateHeader: string | null | undefined): void {
    if (!dateHeader) return;
    const server = Date.parse(dateHeader);
    if (Number.isNaN(server)) return;
    const offset = server - this.now();
    // A Date header has one-second resolution: ignore offsets inside it, so iat never jitters.
    this.offsetMs = Math.abs(offset) < 1500 ? 0 : offset;
  }

  get offset(): number {
    return this.offsetMs;
  }
}

/** The request URL as the proof's `htu`: no query, no fragment (RFC 9449 § 4.2). */
export function htu(url: string): string {
  const cut = url.search(/[?#]/);
  return cut === -1 ? url : url.slice(0, cut);
}

/** `ath`: base64url(SHA-256(access token)). */
export function accessTokenHash(accessToken: string): string {
  return base64url(sha256(utf8(accessToken)));
}

export interface ProofInput {
  method: string;
  url: string;
  /** northline-auth only: the last DPoP-Nonce it sent. */
  nonce?: string;
  /** api calls only: the access token this request carries. */
  accessToken?: string;
}

/** A new proof for one request (every proof works once). */
export async function createProof(key: DeviceKey, clock: ServerClock, input: ProofInput): Promise<string> {
  const header = { typ: 'dpop+jwt', alg: 'ES256', jwk: key.jwk };
  const claims: Record<string, string | number> = {
    jti: randomId(),
    htm: input.method.toUpperCase(),
    htu: htu(input.url),
    iat: clock.seconds(),
  };
  if (input.nonce) claims.nonce = input.nonce;
  if (input.accessToken) claims.ath = accessTokenHash(input.accessToken);
  const signingInput = `${base64url(utf8(JSON.stringify(header)))}.${base64url(utf8(JSON.stringify(claims)))}`;
  const signature = await key.sign(utf8(signingInput));
  return `${signingInput}.${base64url(signature)}`;
}
