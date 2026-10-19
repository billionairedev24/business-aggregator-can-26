// RFC 6238 codes as northline-auth checks them (SHA-1, 30 s, 6 digits) — the same algorithm as web/e2e/src/totp.ts.
import { createHmac } from 'node:crypto';

export function base32Decode(secret) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0;
  const out = [];
  for (const ch of secret.toUpperCase().replace(/[\s=-]/g, '')) {
    const index = alphabet.indexOf(ch);
    if (index < 0) throw new Error('not a base32 key');
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}

export function totpAt(secret, step) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const mac = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = mac[mac.length - 1] & 0x0f;
  return String((mac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

const STEP_MS = 30_000;
const lastStep = new Map(); // auth refuses a step the same person already used (replay protection)

/** A code for a step northline-auth hasn't seen from this key in this run (waits for the next step when needed). */
export async function freshTotp(secret) {
  let step = Math.floor(Date.now() / STEP_MS);
  const last = lastStep.get(secret) ?? -1;
  if (step <= last) {
    await new Promise(r => setTimeout(r, (last + 1) * STEP_MS - Date.now() + 250));
    step = Math.floor(Date.now() / STEP_MS);
  }
  lastStep.set(secret, step);
  return totpAt(secret, step);
}
