#!/usr/bin/env node
// S-150: make creds — every seeded persona, the app to use, and the authenticator code for right now (local only).
//
//   node scripts/dev/creds.mjs [WHO]      the table (WHO: a key such as ravi or priya-n, or part of a name / email)
//   node scripts/dev/creds.mjs --markdown the README table (personas.test.mjs keeps README.md equal to it)
//   node scripts/dev/creds.mjs --reset-sql the SQL of make backup-codes-reset
//
// Codes: RFC 6238 (SHA-1, 30 s, 6 digits) from scripts/dev/personas.json — no oathtool. The latest phone codes come
// from northline-auth's log (AUTH_LOG, default .run/logs/auth.log: "Verification code for …"); demo accounts from
// make demo-data (.demo/local.json) are listed too.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  APPS, PERSONAS, STEP_SECONDS, DEV_TOTP_KEY_ID, backupCodeHash, backupCodeId, backupCodes, displayPhone, find,
  sealTotp, seedIv, totpAt,
} from './personas.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const tty = process.stdout.isTTY && !process.env.NO_COLOR;
const c = code => s => (tty ? `\x1b[${code}m${s}\x1b[0m` : s);
const bold = c(1), dim = c(2), green = c(32), cyan = c(36), yellow = c(33);

/** "Verification code for +1 403 555 0148: 123456" lines of the auth log, latest last. */
export function phoneCodes(log) {
  const out = [];
  for (const line of log.split(/\r?\n/)) {
    const m = /Verification code for (\+1 \d{3} \d{3} \d{4}): (\d{6})/.exec(line);
    if (m) out.push({ phone: m[1], code: m[2], at: /^(\d{4}-\d\d-\d\d[T ][\d:]+)/.exec(line)?.[1] ?? '' });
  }
  return out;
}

export function markdownTable() {
  // `password` is optional per persona (email + password sign-in, S-154): the column appears once any persona has one.
  const pw = PERSONAS.some(p => p.password);
  const rows = PERSONAS.map(p => `| \`${p.key}\` | ${p.name}: ${p.who} | ${p.apps.map(a => APPS[a].name).join(', ')} | \`${p.email}\` · \`${displayPhone(p.phone)}\` |${pw ? ` ${p.password ? `\`${p.password}\`` : '—'} |` : ''} \`${p.totpSecret}\` | \`${p.backupCodePrefix}-00001\` … \`-00010\` |`);
  const head = `| WHO= | persona | app | email · mobile |${pw ? ' password |' : ''} authenticator key | backup codes |`;
  return [head, head.replace(/[^|]+/g, '---'), ...rows].join('\n');
}

/** make backup-codes-reset: the seeded backup codes (unused again) and authenticator keys, for every persona. */
export function resetSql(at = new Date().toISOString()) {
  const ids = PERSONAS.map(p => `'${p.id}'`).join(', ');
  const codes = PERSONAS.flatMap(p => backupCodes(p).map((code, i) => `('${backupCodeId(p, i + 1)}', '${p.id}', '${backupCodeHash(code)}')`));
  const keys = PERSONAS.map(p => `('${p.id}', decode('${sealTotp(p.totpSecret, seedIv(p)).toString('hex')}', 'hex'))`);
  return `BEGIN;
DELETE FROM auth.backup_codes WHERE user_id IN (${ids});
INSERT INTO auth.backup_codes (id, user_id, code_hash, created_at)
SELECT v.id, v.user_id, v.code_hash, '${at}' FROM (VALUES
  ${codes.join(',\n  ')}
) AS v (id, user_id, code_hash) JOIN identity.users u ON u.id = v.user_id;
INSERT INTO auth.totp_secrets (user_id, secret_enc, key_id, confirmed_at)
SELECT v.user_id, v.secret_enc, '${DEV_TOTP_KEY_ID}', '${at}' FROM (VALUES
  ${keys.join(',\n  ')}
) AS v (user_id, secret_enc) JOIN identity.users u ON u.id = v.user_id
ON CONFLICT (user_id) DO UPDATE SET secret_enc = excluded.secret_enc, key_id = excluded.key_id, last_used_step = NULL;
SELECT count(DISTINCT user_id) AS personas, count(*) AS backup_codes FROM auth.backup_codes WHERE user_id IN (${ids}) AND used_at IS NULL;
COMMIT;
`;
}

function demoAccounts() {
  const file = join(ROOT, '.demo', 'local.json');
  if (!existsSync(file)) return [];
  try {
    return Object.entries(JSON.parse(readFileSync(file, 'utf8')).accounts ?? {}).filter(([, a]) => a?.identifier);
  } catch {
    return [];
  }
}

function show(who) {
  const people = find(who);
  if (!people.length) {
    console.error(`No persona matches WHO=${who}. Keys: ${PERSONAS.map(p => p.key).join(', ')}`);
    process.exit(1);
  }
  const now = Date.now() / 1000;
  const step = Math.floor(now / STEP_SECONDS);
  const left = STEP_SECONDS - Math.floor(now % STEP_SECONDS);
  const logFile = process.env.AUTH_LOG || join(ROOT, '.run', 'logs', 'auth.log');
  const sms = existsSync(logFile) ? phoneCodes(readFileSync(logFile, 'utf8')) : [];
  const lastSms = phone => sms.filter(s => s.phone === displayPhone(phone)).at(-1);
  const fmt = code => `${code.slice(0, 3)} ${code.slice(3)}`;
  const single = people.length === 1;

  console.log(`${bold('Local sign-in')} ${dim(`(dev seed, local profile only) — authenticator codes change in ${left} s`)}`);
  console.log(dim('Sign in with the email or mobile → "Authenticator app" → the code. Or a backup code (make backup-codes-reset makes used ones work again).'));
  for (const [app, meta] of Object.entries(APPS)) {
    const group = people.filter(p => p.apps[0] === app);
    if (!group.length) continue;
    console.log(`\n${bold(meta.name)}  ${cyan(meta.url)}`);
    for (const p of group) {
      const code = totpAt(p.totpSecret, step);
      console.log(`  ${bold(p.name.padEnd(16))} ${yellow(`WHO=${p.key}`.padEnd(16))} ${p.email.padEnd(28)} ${displayPhone(p.phone).padEnd(16)} ${green(fmt(code))} ${dim(`(${left} s)`)}`);
      const also = p.apps.slice(1).map(a => APPS[a].name);
      console.log(dim(`  ${''.padEnd(16)} ${p.who}${also.length ? ` · also ${also.join(', ')}` : ''}`));
      const codes = backupCodes(p);
      console.log(dim(`  ${''.padEnd(16)} backup codes ${single ? codes.join(' ') : `${codes[0]} … ${codes.at(-1)}`} · key ${p.totpSecret}`));
      if (p.password) console.log(`  ${''.padEnd(16)} ${dim('password')} ${green(p.password)}`);
      const s = lastSms(p.phone);
      if (s) console.log(`  ${''.padEnd(16)} ${dim('latest phone code')} ${green(s.code)} ${dim(s.at)}`);
    }
  }
  if (!who) {
    const demo = demoAccounts();
    if (demo.length) {
      console.log(`\n${bold('Demo accounts')} ${dim('(make demo-data, .demo/local.json)')}`);
      for (const [key, a] of demo) {
        const code = a.totpSecret ? `${green(fmt(totpAt(a.totpSecret, step)))} ${dim(`(${left} s)`)}` : dim('backup codes in .demo/local.json');
        console.log(`  ${bold((a.name ?? key).slice(0, 16).padEnd(16))} ${yellow(key.slice(0, 16).padEnd(16))} ${String(a.identifier).padEnd(28)} ${String(a.phone ?? '').padEnd(16)} ${code}`);
      }
    }
    const latest = sms.at(-1);
    console.log('');
    if (latest) console.log(`${dim('Latest phone code in')} ${dim(logFile.replace(ROOT + '/', ''))}: ${green(latest.code)} ${dim(`for ${latest.phone} ${latest.at}`)}`);
    else console.log(dim(`No phone codes yet (northline-auth writes "Verification code for …" to ${logFile.replace(ROOT + '/', '')}).`));
    console.log(dim('One persona: make creds WHO=amara · in an authenticator app: add the key shown (time-based, 6 digits, 30 s).'));
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const arg = (process.argv[2] ?? '').trim();
  if (arg === '--markdown') console.log(markdownTable());
  else if (arg === '--reset-sql') process.stdout.write(resetSql());
  else show(arg);
}
