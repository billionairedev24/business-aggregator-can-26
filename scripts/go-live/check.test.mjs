// node --test scripts/go-live/check.test.mjs — the repository parsers of the go-live check (S-118).
import assert from 'node:assert/strict';
import { test } from 'node:test';

import * as m from './parsers.mjs';

test('security findings: an open high fails, fixed and low pass', () => {
  const md = '| id | severity | area | finding | status |\n|---|---|---|---|---|\n| A | High (7.5) | x | y | fixed |\n| B | Low | x | y | open |\n';
  assert.equal(m.securityFindings(md).status, 'pass');
  assert.equal(m.securityFindings(md.replace('| fixed |', '| open |')).status, 'fail');
});

test('a11y: an open serious issue fails', () => {
  const md = '| # | criterion | severity | where | issue | status |\n|---|---|---|---|---|---|\n| 1 | 4.1.2 | critical | a | b | fixed: done |\n| 2 | 1.4.3 | moderate | a | b | open |\n';
  assert.equal(m.a11yCriticals(md).status, 'pass');
  assert.equal(m.a11yCriticals(md.replace('| moderate |', '| serious |')).status, 'fail');
});

test('drill log: outside local only a cloud drill counts, and it must be recent', () => {
  const md = '| date | where | by | result |\n|---|---|---|---|\n| 2026-10-02 | local (S-114) | S-114 | **passed** |\n';
  const now = Date.parse('2026-10-20');
  assert.equal(m.backupDrill(md, 'local', now).status, 'pass');
  assert.equal(m.backupDrill(md, 'prod', now).status, 'fail');
  assert.equal(m.backupDrill(md.replace('local (S-114)', 'prod, aws'), 'prod', now).status, 'pass');
  assert.equal(m.backupDrill(md, 'local', Date.parse('2027-03-01')).status, 'fail');
});

test('legal registry: every current version needs a sign-off', () => {
  const signed = { documents: [{ id: 'terms', versions: [{ signOff: null }, { signOff: { counsel: 'X' } }] }] };
  assert.equal(m.legalSignoff(signed).status, 'pass');
  assert.equal(m.legalSignoff({ documents: [...signed.documents, { id: 'privacy', versions: [{ signOff: null }] }] }).status, 'fail');
});

test('e2e and load results: failures, crossed thresholds and age', () => {
  const now = Date.parse('2026-10-05T12:00:00Z');
  const run = { stats: { startTime: '2026-10-04T10:00:00Z', expected: 30, unexpected: 0, flaky: 1 } };
  assert.equal(m.e2eResults(run, 0, now).status, 'pass');
  assert.equal(m.e2eResults({ stats: { ...run.stats, unexpected: 2 } }, 0, now).status, 'fail');
  assert.equal(m.e2eResults({ stats: { ...run.stats, startTime: '2026-09-01T00:00:00Z' } }, 0, now).status, 'fail');
  const held = { metrics: { http_req_duration: { thresholds: { 'p(95)<2000': false } } } };
  assert.equal(m.loadTest(held, 'run', now - 3600_000, now).status, 'pass');
  assert.equal(m.loadTest({ metrics: { http_req_duration: { thresholds: { 'p(95)<2000': true } } } }, 'run', now, now).status, 'fail');
});
