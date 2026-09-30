#!/usr/bin/env node
// Lint: no hex colour literals in components (CLAUDE.md — packages/tokens/tokens.json is the ONLY place colours are
// defined; components use var(--*)). Scans packages/ui/src, packages/auth-kit/src and apps/*/src (.tsx, .ts, .css), skipping tests and
// test fixtures. Legitimate exceptions (third-party brand artwork, merchant data) live in hex-colors.allowlist.
//
//   node scripts/check-hex-colors.mjs        (from web/, or `pnpm lint:colors`)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const roots = ['packages/ui/src', 'packages/auth-kit/src', ...readdirSync(path.join(webRoot, 'apps')).map(a => `apps/${a}/src`)];
const extensions = new Set(['.tsx', '.ts', '.css']);
const skip = [/\.test\.tsx?$/, /\.spec\.tsx?$/, /(^|\/)test\//, /(^|\/)__tests__\//, /(^|\/)node_modules\//, /\.gen\.ts$/, /routeTree\.gen\.ts$/];

// `#` + 3, 4, 6 or 8 hex digits, not part of a word, an HTML entity (&#123;) or a longer token.
const HEX = /(?<![\w&#])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})(?![\w-])/g;

/** Allowlist: one repo-relative (to web/) path or glob per line; `*` = within a segment, `**` = any depth. */
function loadAllowlist() {
  const file = path.join(webRoot, 'scripts/hex-colors.allowlist');
  return readFileSync(file, 'utf8')
    .split('\n')
    .map(l => l.replace(/#\s.*$/, '').trim())
    .filter(Boolean)
    .map(glob => new RegExp('^' + glob.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*\*\/?/g, '\u0000').replace(/\*/g, '[^/]*').replace(/\u0000/g, '(?:.*/)?') + '$'));
}

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) yield* walk(full);
    else yield full;
  }
}

const allow = loadAllowlist();
const problems = [];
for (const root of roots) {
  const abs = path.join(webRoot, root);
  try { statSync(abs); } catch { continue; }
  for (const file of walk(abs)) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (!extensions.has(path.extname(rel)) || skip.some(r => r.test(rel)) || allow.some(r => r.test(rel))) continue;
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      for (const m of line.matchAll(HEX)) problems.push(`${rel}:${i + 1}:${m.index + 1}  ${m[0]}`);
    });
  }
}

if (problems.length) {
  console.error(`Hex colour literals found (use var(--color-*) from @northline/tokens instead):\n${problems.map(p => '  ' + p).join('\n')}`);
  console.error(`\n${problems.length} problem(s). Genuine exceptions go in web/scripts/hex-colors.allowlist with a reason.`);
  process.exit(1);
}
console.log(`No hex colour literals in ${roots.join(', ')}.`);
