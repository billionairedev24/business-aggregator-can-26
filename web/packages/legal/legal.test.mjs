// The committed legal pages are exactly what scripts/legal-pages.mjs makes of design 09/10 (verbatim, CLAUDE.md).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LEGAL_DIR, legalFiles } from './vite.mjs';

const repo = fileURLToPath(new URL('../../../', import.meta.url));

test('the pages are the generator’s output of the design', () => {
  const out = mkdtempSync(join(tmpdir(), 'legal-'));
  execFileSync(process.execPath, [join(repo, 'scripts/legal-pages.mjs'), out]);
  assert.deepEqual(readdirSync(out).sort(), legalFiles().sort());
  for (const name of readdirSync(out)) {
    assert.equal(readFileSync(join(LEGAL_DIR, name), 'utf8'), readFileSync(join(out, name), 'utf8'), `${name} differs: run node scripts/legal-pages.mjs`);
  }
});

test('terms and privacy keep the design’s headings and link each other', () => {
  const terms = readFileSync(join(LEGAL_DIR, 'terms.html'), 'utf8');
  const privacy = readFileSync(join(LEGAL_DIR, 'privacy.html'), 'utf8');
  assert.match(terms, /<title>Terms of Service · Northline<\/title>/);
  assert.match(privacy, /<title>Privacy Policy · Northline<\/title>/);
  assert.match(terms, /id="business"/);
  assert.match(terms, /href="privacy\.html/);
  assert.doesNotMatch(terms + privacy, /support\.js|<x-dc|<helmet/);
});
