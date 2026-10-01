import { p256 } from '@noble/curves/nist.js';
import { sha256 } from '@noble/hashes/sha2.js';

import { base64url, fromBase64url, utf8 } from '../bytes';
import type { SecureStorage } from '../storage/secure';
import { randomBytes } from './random';

/** The public half of the app's key, as it goes into every DPoP proof's header (never `d`). */
export interface PublicJwk {
  kty: 'EC';
  crv: 'P-256';
  x: string;
  y: string;
}

/**
 * The key the app's tokens are bound to (DPoP, RFC 9449; docs/runbooks/mobile-auth.md § 1). One per installation and
 * sign-in: created at sign-in, deleted at sign-out. `sign` returns the JWS ES256 signature (R ‖ S, 64 bytes).
 *
 * The runbook asks for a non-exportable key in the Secure Enclave / Android Keystore. Expo has no module for that yet,
 * so {@link SoftwareDeviceKey} keeps the P-256 private key in the platform's secure storage (Keychain / Keystore-
 * encrypted, this device only) and signs in JS. A hardware-backed implementation of this interface (a native module)
 * replaces it without touching the callers: DECISIONS S-87.
 */
export interface DeviceKey {
  readonly jwk: PublicJwk;
  /** RFC 7638 thumbprint: what the server puts in the token's `cnf.jkt`. */
  readonly thumbprint: string;
  sign(data: Uint8Array): Promise<Uint8Array>;
}

export function thumbprintOf(jwk: PublicJwk): string {
  // RFC 7638 § 3.2: the required members, lexicographic order, no whitespace.
  const canonical = `{"crv":"${jwk.crv}","kty":"${jwk.kty}","x":"${jwk.x}","y":"${jwk.y}"}`;
  return base64url(sha256(utf8(canonical)));
}

export class SoftwareDeviceKey implements DeviceKey {
  readonly jwk: PublicJwk;
  readonly thumbprint: string;

  private constructor(private readonly secret: Uint8Array) {
    const point = p256.getPublicKey(secret, false); // 0x04 ‖ X ‖ Y
    this.jwk = { kty: 'EC', crv: 'P-256', x: base64url(point.slice(1, 33)), y: base64url(point.slice(33, 65)) };
    this.thumbprint = thumbprintOf(this.jwk);
  }

  static generate(random: (n: number) => Uint8Array = randomBytes): SoftwareDeviceKey {
    // 48 bytes of seed reduced mod n (FIPS 186-5 A.2.1): no bias, never zero.
    return new SoftwareDeviceKey(p256.utils.randomSecretKey(random(48)));
  }

  static fromSecret(secret: Uint8Array): SoftwareDeviceKey {
    if (!p256.utils.isValidSecretKey(secret)) throw new Error('Not a P-256 secret key');
    return new SoftwareDeviceKey(secret);
  }

  async sign(data: Uint8Array): Promise<Uint8Array> {
    return p256.sign(data, this.secret, { prehash: true, format: 'compact', lowS: false });
  }

  /** For secure storage only. */
  exportSecret(): string {
    return base64url(this.secret);
  }
}

const KEY_ITEM = 'nl.dpop.key.v1';

/** The key held for this installation, if a sign-in created one. */
export async function loadDeviceKey(storage: SecureStorage): Promise<DeviceKey | null> {
  const stored = await storage.get(KEY_ITEM);
  if (!stored) return null;
  try {
    return SoftwareDeviceKey.fromSecret(fromBase64url(stored));
  } catch {
    await storage.remove(KEY_ITEM);
    return null;
  }
}

/** A new key for a new sign-in (the old one, if any, is replaced: its tokens die with it). */
export async function createDeviceKey(storage: SecureStorage): Promise<DeviceKey> {
  const key = SoftwareDeviceKey.generate();
  await storage.set(KEY_ITEM, key.exportSecret());
  return key;
}

export async function deleteDeviceKey(storage: SecureStorage): Promise<void> {
  await storage.remove(KEY_ITEM);
}
