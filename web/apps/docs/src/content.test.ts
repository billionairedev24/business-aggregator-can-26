import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { REPO_DOCS_EXCLUDE, SPECS, specsFor, variantFrom } from './content';

const repoDocs = resolve(import.meta.dirname, '../../../../docs');

describe('the documentation site content (S-126)', () => {
  it('lists every committed OpenAPI spec exactly once, and only those', () => {
    const committed = readFileSync(resolve(repoDocs, '../redocly.yaml'), 'utf8')
      .split('\n')
      .map((line) => /^\s+root: docs\/api\/openapi\/(.+)\.yaml$/.exec(line)?.[1])
      .filter((id): id is string => id !== undefined)
      .sort();
    expect(SPECS.map((s) => s.id).sort()).toEqual(committed);
    for (const spec of SPECS) {
      expect(existsSync(resolve(repoDocs, 'api/openapi', `${spec.id}.yaml`)), spec.id).toBe(true);
    }
  });

  it('never publishes an internal spec in the public variant', () => {
    const pub = specsFor('public').map((s) => s.id);
    expect(pub).toEqual(['api-public', 'api-partner', 'api-webhooks', 'auth-public']);
    expect(specsFor('public').every((s) => s.audience === 'public')).toBe(true);
    expect(specsFor('internal')).toHaveLength(SPECS.length);
  });

  it('has an English and a French title and summary for every spec', () => {
    for (const spec of SPECS) {
      for (const locale of ['en', 'fr'] as const) {
        expect(spec.title[locale].length, `${spec.id} ${locale}`).toBeGreaterThan(3);
        expect(spec.summary[locale].length, `${spec.id} ${locale}`).toBeGreaterThan(10);
      }
    }
  });

  it('keeps the machine-readable specs and the agents’ brief out of the rendered docs', () => {
    expect(REPO_DOCS_EXCLUDE).toEqual(expect.arrayContaining(['spec/**', 'api/**', 'WORKSTREAM_BRIEF.md']));
  });

  it('reads the variant, internal by default, and refuses anything else', () => {
    expect(variantFrom(undefined)).toBe('internal');
    expect(variantFrom('public')).toBe('public');
    expect(() => variantFrom('everything')).toThrow(/public" or "internal/);
  });
});
