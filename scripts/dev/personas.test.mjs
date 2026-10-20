// S-150: scripts/dev/personas.json, the dev seed (db/seed-dev) and README.md agree (node --test; make creds-test).
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { markdownTable, phoneCodes, resetSql } from './creds.mjs';
import {
  APPS, PERSONAS, backupCodeHash, backupCodeId, backupCodes, find, openTotp, sealTotp, seedIv, totpAt,
} from './personas.mjs';

const ROOT = join(import.meta.dirname, '..', '..');
const SEED = join(ROOT, 'db', 'seed-dev');
const sql = readdirSync(SEED).filter(f => f.endsWith('.sql')).sort().map(f => readFileSync(join(SEED, f), 'utf8')).join('\n');

const totpRows = new Map(); // user id → [secret_enc]
for (const m of sql.matchAll(/\('(01J9ZD3V\w{18})',\s*decode\('([0-9a-f]+)', 'hex'\)/g)) {
  totpRows.set(m[1], [...(totpRows.get(m[1]) ?? []), Buffer.from(m[2], 'hex')]);
}
const codeRows = [...sql.matchAll(/\('(01J9ZD3V\w{18})', '(01J9ZD3V\w{18})', '([0-9a-f]{64})'/g)].map(m => ({ id: m[1], user: m[2], hash: m[3] }));

test('every persona is a seeded user with that id, mobile and email', () => {
  for (const p of PERSONAS) {
    assert.ok(sql.includes(`('${p.id}', '${p.phone}', '${p.email}'`), `${p.key}: ${p.id} ${p.phone} ${p.email} not in db/seed-dev`);
  }
});

test('the list is well formed: unique keys, ids, keys and code tags; known apps; 32-character base32 keys', () => {
  for (const field of ['key', 'id', 'email', 'phone', 'totpSecret', 'backupCodePrefix', 'codeIdTag']) {
    assert.equal(new Set(PERSONAS.map(p => p[field])).size, PERSONAS.length, `${field} repeats`);
  }
  for (const p of PERSONAS) {
    assert.match(p.totpSecret, /^NORTHLINE[A-Z]+DEVTOTPSECRET[A-Z2-7]*$/, `${p.key}: an obviously dev-only key`);
    assert.equal(p.totpSecret.length, 32, p.key);
    assert.ok(p.apps.length && p.apps.every(a => APPS[a]), `${p.key}: apps`);
    assert.ok(p.password === undefined || (typeof p.password === 'string' && p.password.length > 0), `${p.key}: password`);
  }
});

test("each persona's authenticator key is the one the seed stores (decrypted with the dev key)", () => {
  for (const p of PERSONAS) {
    const rows = totpRows.get(p.id) ?? [];
    assert.equal(rows.length, 1, `${p.key}: ${rows.length} auth.totp_secrets rows in db/seed-dev`);
    assert.equal(openTotp(rows[0]), p.totpSecret, `${p.key}: the seeded key differs from personas.json`);
  }
});

test("each persona's ten backup codes are the ones the seed stores", () => {
  for (const p of PERSONAS) {
    const seeded = codeRows.filter(r => r.user === p.id);
    assert.deepEqual(seeded.map(r => r.hash).sort(), backupCodes(p).map(backupCodeHash).sort(), `${p.key}: backup codes`);
    assert.deepEqual(seeded.map(r => r.id).sort(), backupCodes(p).map((_, i) => backupCodeId(p, i + 1)).sort(), `${p.key}: ids`);
  }
});

test('S-154: every persona signs in with the dev password the seed hashes (db/seed-dev/V416)', () => {
  const seed = readFileSync(join(ROOT, 'db/seed-dev/V416__dev_passwords.sql'), 'utf8');
  for (const p of PERSONAS) {
    assert.equal(p.password, 'northline-dev-password', `${p.key}: password`);
    assert.ok(p.email.endsWith('@example.com'), `${p.key}: the seed sets passwords for @example.com accounts`);
  }
  assert.ok(seed.includes('northline-dev-password'));
});

test('README.md carries the generated table', () => {
  const readme = readFileSync(join(ROOT, 'README.md'), 'utf8');
  assert.ok(readme.includes(markdownTable()), 'README.md § Local sign-in: paste the output of node scripts/dev/creds.mjs --markdown');
});

test('a password shows in the table when a persona has one (S-154 fills them in)', () => {
  const p = PERSONAS[0];
  try {
    p.password = 'dev-only-password';
    assert.match(markdownTable(), /\| password \|/);
    assert.ok(markdownTable().includes('`dev-only-password`'));
  } finally {
    delete p.password;
  }
  if (!PERSONAS.some(x => x.password)) assert.doesNotMatch(markdownTable(), /password/);
});

test('codes: RFC 6238 SHA-1 vector, the seal round-trips, IVs are 12 bytes', () => {
  assert.equal(totpAt('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1), '287082'); // T = 59 s
  for (const p of PERSONAS) {
    assert.equal(seedIv(p).length, 12);
    assert.equal(openTotp(sealTotp(p.totpSecret, seedIv(p))), p.totpSecret);
  }
  assert.equal(backupCodeHash('ravis-00001'), '19cac73e3754dce420b83c7f52aa672ecf348eae55d54862c1df21287b3eb5d9'); // V101
});

test('WHO= finds a key exactly, else by part of a name or email', () => {
  assert.deepEqual(find('priya').map(p => p.key), ['priya']);
  assert.deepEqual(find('natarajan').map(p => p.key), ['priya-n']);
  assert.deepEqual(find('Belanger').map(p => p.key), ['marc']);
  assert.equal(find('').length, PERSONAS.length);
  assert.equal(find('nobody-here').length, 0);
});

test('phone codes are read from the auth log, latest last', () => {
  const log = [
    '2026-10-20T16:00:00.000Z  WARN 1 --- [auth] c.n.a.s.LoggingSmsSender : [SMS] Verification code for +1 403 555 0201: 123456 (not sent — local SMS fake)',
    'something else',
    '2026-10-20T16:01:00.000Z  WARN 1 --- [auth] c.n.a.s.LoggingSmsSender : [SMS] Verification code for +1 587 555 0101: 654321 (not sent — local SMS fake)',
  ].join('\n');
  assert.deepEqual(phoneCodes(log), [
    { phone: '+1 403 555 0201', code: '123456', at: '2026-10-20T16:00:00' },
    { phone: '+1 587 555 0101', code: '654321', at: '2026-10-20T16:01:00' },
  ]);
});

test('the reset SQL restores every persona, in one transaction', () => {
  const s = resetSql('2026-10-20T00:00:00Z');
  assert.match(s, /^BEGIN;/);
  assert.match(s, /COMMIT;\n$/);
  for (const p of PERSONAS) assert.ok(s.includes(backupCodeHash(backupCodes(p).at(-1))), p.key);
  assert.equal(s.match(/decode\('/g).length, PERSONAS.length);
});
