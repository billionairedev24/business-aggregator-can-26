// S-150: the seeded personas (personas.json) and how the dev seed stores their sign-in factors — shared by make creds,
// make backup-codes-reset and personas.test.mjs. Local only: the key below is the dev-only northline.auth.totp-key of
// server/auth/src/main/resources/application-local.yml (and application-test.yml), never valid anywhere else.
import { createCipheriv, createDecipheriv, createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

export { totpAt } from '../demo/lib/totp.mjs';

const data = JSON.parse(readFileSync(new URL('./personas.json', import.meta.url), 'utf8'));
export const APPS = data.apps;
export const PERSONAS = data.personas;

/** northline.auth.totp-key under `local` (base64 of 32 bytes); the seed's key id is the default TOTP_KEY_ID. */
export const DEV_TOTP_KEY = Buffer.from('bm9ydGhsaW5lLWxvY2FsLWRldi10b3RwLWtleS0zMmI=', 'base64');
export const DEV_TOTP_KEY_ID = 'v1';
export const BACKUP_CODE_COUNT = 10;
export const STEP_SECONDS = 30;

/** auth.backup_codes.code_hash: sha256('backup:' || the code lower-cased without spaces or dashes) — BackupCodes.hash. */
export function backupCodeHash(code) {
  return createHash('sha256').update('backup:' + code.replace(/[\s-]/g, '').toLowerCase()).digest('hex');
}

export function backupCodes(persona) {
  return Array.from({ length: BACKUP_CODE_COUNT }, (_, i) => `${persona.backupCodePrefix}-${String(i + 1).padStart(5, '0')}`);
}

/** auth.backup_codes.id of the n-th (1-based) seeded code: 01J9ZD3V000000000000B<tag><nn>, 26 characters. */
export function backupCodeId(persona, n) {
  return `01J9ZD3V000000000000B${persona.codeIdTag}${String(n).padStart(2, '0')}`;
}

/** auth.totp_secrets.secret_enc as SecretCipher writes it: 12-byte IV ‖ AES-256-GCM ciphertext ‖ 16-byte tag. */
export function sealTotp(secret, iv) {
  if (iv.length !== 12) throw new Error('the IV is 12 bytes');
  const cipher = createCipheriv('aes-256-gcm', DEV_TOTP_KEY, iv);
  return Buffer.concat([iv, cipher.update(secret, 'utf8'), cipher.final(), cipher.getAuthTag()]);
}

export function openTotp(box) {
  const decipher = createDecipheriv('aes-256-gcm', DEV_TOTP_KEY, box.subarray(0, 12));
  decipher.setAuthTag(box.subarray(box.length - 16));
  return Buffer.concat([decipher.update(box.subarray(12, box.length - 16)), decipher.final()]).toString('utf8');
}

/** The seed's fixed IV for a persona (a dev seed must be reproducible): "<key>-dev-iv", cut or padded to 12 bytes. */
export function seedIv(persona) {
  return Buffer.from(`${persona.codeIdTag.toLowerCase()}-dev-iv-0000`.slice(0, 12));
}

/** Persona by key, else every persona whose key, name or email contains `who` (case and accents ignored). */
export function find(who) {
  if (!who) return PERSONAS;
  const fold = s => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const exact = PERSONAS.filter(p => p.key === who.toLowerCase());
  if (exact.length) return exact;
  const w = fold(who);
  return PERSONAS.filter(p => [p.key, p.name, p.email, p.phone].some(f => fold(f).includes(w)));
}

/** "+14035550148" → "+1 403 555 0148" (PhoneNumber.display, as the auth log writes it). */
export function displayPhone(e164) {
  return `+1 ${e164.slice(2, 5)} ${e164.slice(5, 8)} ${e164.slice(8)}`;
}
