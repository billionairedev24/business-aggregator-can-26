// The committed legal pages are exactly what scripts/legal-pages.mjs makes of design 09/10 (verbatim, CLAUDE.md).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LEGAL_DIR, legalFiles } from './vite.mjs';
import { REPO, currentVersion, documentText, pageText, pageVersionLine, readRegistry, registryProblems, sha256 } from './registry.mjs';

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

// S-106: the legal document registry (registry.json, registry.mjs) — version, effective date and counsel's sign-off of
// every legal text. "Pages updated verbatim" once counsel returns text = the generator test above + these.

test('every legal text matches its registered version (a changed text needs a version bump)', () => {
  assert.deepEqual(registryProblems(), []);
});

test('a changed page without a version bump is caught, and so is a sign-off of another text', () => {
  const registry = readRegistry();
  const terms = registry.documents.find(d => d.id === 'terms');
  const edited = structuredClone(registry);
  edited.documents.find(d => d.id === 'terms').versions[0].sha256 = sha256(documentText(terms) + ' changed');
  assert.ok(registryProblems(edited).some(p => p.startsWith('terms: the text changed without a version bump')));

  const signed = structuredClone(registry);
  signed.documents.find(d => d.id === 'privacy').versions[0].signOff = { counsel: 'A. Counsel', date: '2027-07-20', sha256: '0'.repeat(64) };
  assert.ok(registryProblems(signed).some(p => p.includes('counsel signed off a different text')));

  const bumped = structuredClone(registry);
  bumped.documents.find(d => d.id === 'terms').versions.push({ version: '3.1', effective: '2027-08-01', sha256: currentVersion(terms).sha256 });
  const problems = registryProblems(bumped);
  assert.ok(problems.some(p => p.includes('two versions have the same text')));
  assert.ok(problems.some(p => p.includes('the page says version 3.0')));
});

test('the visible text ignores markup and styling', () => {
  assert.equal(pageText('<p style="x">Hello&nbsp;<b>world</b> &amp; co</p><style>p{}</style>'), 'Hello world & co');
  assert.deepEqual(pageVersionLine('Version 3.0 · Effective 1 October 2026. More'), { version: '3.0', effective: '2026-10-01' });
});

test('versions stored elsewhere agree with the registry', () => {
  const registry = readRegistry();
  const read = path => readFileSync(join(REPO, path), 'utf8');
  const version = id => currentVersion(registry.documents.find(d => d.id === id)).version;
  // northline-auth stores this as identity.users.terms_version at registration
  assert.match(read('server/auth/src/main/java/ca/northline/auth/application/AuthProperties.java'), new RegExp(`@DefaultValue\\("${version('terms').replace('.', '\\.')}"\\) String termsVersion`));
  assert.equal(version('terms'), version('privacy'), 'one terms_version covers both documents');
  assert.match(read('server/api/src/main/java/ca/northline/merchants/domain/ComplianceRules.java'), new RegExp(`OBLIGATIONS_VERSION = "${version('merchant-obligations').replace('.', '\\.')}"`));
  const wordings = [...read('server/api/src/main/java/ca/northline/messaging/domain/ConsentWordings.java').matchAll(/new Wording\(\s*"([^"]+)"/g)].map(m => m[1]);
  assert.deepEqual(registry.documents.find(d => d.id === 'casl-consent-wordings').versions.map(v => v.version).sort(), wordings.sort());
});
