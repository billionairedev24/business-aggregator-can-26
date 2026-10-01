import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/*
 * S-134 region-neutral lint (DECISIONS "Region-neutral by design"): no province, city or time zone in the web apps'
 * and packages' code or copy. Place names, zones and laws come from the region model (`GET /api/v1/geo/regions`, the
 * merchant's `region`) and reach copy as {province}/{city}/{privacyLaw} parameters. Tests, stories and fixtures may keep
 * Calgary data; anything else needs an entry in ALLOWED with its reason.
 */
const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');

const PLACE = /Alberta|Calgary|Edmonton|Airdrie|Beltline|British Columbia|Ontario|Qu[eé]bec|Manitoba|Saskatchewan|Nova Scotia|New Brunswick|Newfoundland|Prince Edward Island|Yukon|Nunavut|Northwest Territories|America\/(?:Edmonton|Vancouver|Toronto|Winnipeg|Regina|Halifax|St_Johns|Moncton|Whitehorse|Iqaluit|Yellowknife)/;
const CODE = /^(?:AB|BC|MB|NB|NS|NT|NU|ON|PE|QC|SK|YT)$/;

/** file (relative to web/) → literal fragments allowed there, each with its reason. */
const ALLOWED: Record<string, { fragment: RegExp; reason: string }[]> = {
  'apps/studio/src/features/compliance/messages.ts': [
    { fragment: /French required when serving Québec|français requis pour servir le Québec/, reason: 'a fact about one named province’s language law, not the business’s place' },
  ],
  'apps/consumer/src/features/account/settingsMessages.ts': [
    { fragment: /requis au Québec/, reason: 'design 06 Language tab: a fact about one named province’s language law, not the person’s place' },
  ],
  'apps/studio/src/features/onboarding/legal.ts': [
    { fragment: /^(?:BC|SK|MB|ON|QC)$/, reason: 'home-jurisdiction values of docs/spec/legal-details.schema.json (the spec’s enum; labels come from the region model)' },
  ],
};

const SKIP = /(\.test\.|\.stories\.|fixtures|\/test\/|__tests__|routeTree\.gen\.ts|\.d\.ts$)/;

function files(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const full = path.join(dir, name);
    if (name === 'node_modules' || name === 'dist') return [];
    return statSync(full).isDirectory() ? files(full) : /\.(ts|tsx)$/.test(name) ? [full] : [];
  });
}

/** Comments out, string literals kept (a `//` after `:` is a URL inside a string, not a comment). */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, (_, lead: string) => lead);
}

const LITERAL = /'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|`(?:[^`\\]|\\.)*`/g;

export function regionLiterals(): string[] {
  const roots = ['apps', 'packages'].flatMap(top => readdirSync(path.join(WEB, top)).map(p => path.join(WEB, top, p, 'src')))
    .filter(dir => { try { return statSync(dir).isDirectory(); } catch { return false; } });
  const found: string[] = [];
  for (const file of roots.flatMap(files)) {
    const rel = path.relative(WEB, file).split(path.sep).join('/');
    if (SKIP.test(rel)) continue;
    const code = stripComments(readFileSync(file, 'utf8'));
    for (const match of code.matchAll(LITERAL)) {
      const text = match[0].slice(1, -1);
      const hit = PLACE.exec(text)?.[0] ?? (CODE.test(text) ? text : undefined);
      if (!hit) continue;
      if ((ALLOWED[rel] ?? []).some(a => a.fragment.test(text) || a.fragment.test(hit))) continue;
      const line = code.slice(0, match.index).split('\n').length;
      found.push(`${rel}:${line}: ${hit}`);
    }
  }
  return found;
}

describe('region-neutral web code (S-134)', () => {
  it('names no province, city or time zone outside the region model, tests and fixtures', () => {
    expect(regionLiterals()).toEqual([]);
  });

  it('catches a literal and ignores comments', () => {
    expect(stripComments("// in Calgary\nconst a = 'x'; /* Alberta */ const u = 'https://x.test';")).not.toMatch(PLACE);
    expect(PLACE.test('Join Northline · Alberta')).toBe(true);
    expect(CODE.test('AB')).toBe(true);
  });
});
