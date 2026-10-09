import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Engineering follow-ups: `make i18n-check` (S-116's French coverage gate, scripts/i18n/coverage.mjs) failed on main
 * because nothing ran it with the web checks. This runs the same scanner in `pnpm -r test`, so a string without fr-CA
 * (web, mobile, server messages, catalogue, store, legal, the review file) fails the web checks too. Known gaps (legal
 * texts, French awaiting a translator) are reported by the gate, not failed — as without STRICT=1.
 */
type Gap = { surface: string; key: string; kind: string; en?: string; fr?: string };
type Coverage = { audit: (options?: { strict?: boolean }) => { failing: Gap[] } };

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');

describe('French coverage (make i18n-check)', () => {
  it('every customer-facing string has fr-CA', async () => {
    const scanner = pathToFileURL(path.join(root, 'scripts/i18n/coverage.mjs')).href;
    const { audit } = (await import(/* @vite-ignore */ scanner)) as Coverage;
    const failing = audit().failing.map(g => `[${g.surface}] ${g.kind} ${g.key}${g.en ? ` — "${g.en}"` : ''}`);
    expect(failing, 'write the French in the catalogue and add the row to docs/i18n/translation-review.csv (docs/runbooks/i18n.md)').toEqual([]);
  }, 120_000);
});
