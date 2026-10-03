import { createHmac } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

/** RFC 4648 base32 (the authenticator-app key format), case-insensitive, spaces and padding ignored. */
export function base32Decode(secret: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = secret.toUpperCase().replace(/[\s=-]/g, '');
  let bits = 0, value = 0;
  const out: number[] = [];
  for (const ch of clean) {
    const index = alphabet.indexOf(ch);
    if (index < 0) throw new Error(`not a base32 key (character '${ch}')`);
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}

/** RFC 6238 TOTP as northline-auth checks it: SHA-1, 30-second steps, 6 digits. */
export function totpAt(secret: string, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const mac = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = mac[mac.length - 1]! & 0x0f;
  const code = (mac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return String(code).padStart(6, '0');
}

export const STEP_MS = 30_000;
export const stepOf = (ms: number) => Math.floor(ms / STEP_MS);

/**
 * A code that northline-auth has not seen yet. Auth refuses a code whose step was already used by the same person (replay
 * protection), and the suite signs some personas in more than once (sign-in, step-up). The last step used per key is kept
 * in `stateFile` (shared by every Playwright worker); when the current step is taken, this waits for the next one — the
 * protocol's clock, not a guess (at most 30 s).
 */
export async function freshTotp(secret: string, stateFile: string, now: () => number = Date.now): Promise<string> {
  const used = readState(stateFile);
  const key = secret.replace(/\s/g, '').toUpperCase();
  let step = stepOf(now());
  const last = used[key] ?? -1;
  if (step <= last) {
    const wait = (last + 1) * STEP_MS - now() + 250;
    await new Promise(resolve => setTimeout(resolve, wait));
    step = stepOf(now());
  }
  writeState(stateFile, { ...readState(stateFile), [key]: step });
  return totpAt(secret, step);
}

function readState(file: string): Record<string, number> {
  try { return JSON.parse(readFileSync(file, 'utf8')) as Record<string, number>; } catch { return {}; }
}

function writeState(file: string, state: Record<string, number>) {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify(state));
}
