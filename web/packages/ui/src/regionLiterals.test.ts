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
/*
 * Demo rehearsal 2026-10-19: one province's bodies, zone abbreviations and area code in copy ("AHS vetting", "7 am – 11
 * pm MT", "AGLC licence", "+1 (403)") and the cloud's region name ("ca-central-1"). Regulators come from the region
 * model or the taxonomy data; copy says what they are ("food permit", "liquor licence").
 */
const REGIONAL = /\b(?:AHS|AGLC|AMVIC|RECA|ProServe|MT|MDT|MST)\b|\(403\)|\b(?:ca-central-1)\b/;

/*
 * 2026-10-19 (province legal details): a province in an identifier or a demonym — a field key like
 * `alberta_corporate_access_number`, a structure code like `corp_ab`, "albertain" in French copy. Field keys, codes and
 * copy for a province's legal details come from the region model (`GET …/onboarding/legal-forms`), never from code.
 */
const PROVINCE_KEY = /(?:^|[_.\-])(?:alberta|british_columbia|ontario|quebec|manitoba|saskatchewan|nova_scotia|new_brunswick|newfoundland|prince_edward_island|yukon|nunavut|northwest_territories)(?:[_.\-]|$)|\b(?:albertaine?|ontarienne?|qu[eé]b[eé]coise?|manitobaine?|saskatchewanaise?|n[eé]o-[eé]cossaise?)\b|\bcorp_(?:ab|bc|on|qc|sk|mb|nb|ns|nl|pe|yt|nt|nu)\b/i;

/** file (relative to web/) → literal fragments allowed there, each with its reason. */
const ALLOWED: Record<string, { fragment: RegExp; reason: string }[]> = {
  'apps/studio/src/features/compliance/docs.ts': [
    { fragment: /^(?:AMVIC|AGLC)$/, reason: 'a registry code the server sends (merchants.registry_checks.source, taxonomy data), compared to pick a note — not copy' },
  ],
  'apps/studio/src/features/onboarding/checks.ts': [
    { fragment: /^AMVIC$/, reason: 'a registry code the server sends (taxonomy data), compared to pick a description — not copy' },
  ],
  'apps/studio/src/features/onboarding/CategoryPicker.tsx': [
    { fragment: /AMVIC|AGLC|AHS|ProServe/, reason: 'French for the taxonomy seed\u2019s English group notes (db/seed/categories.json, served as data): keys must match the data until the seed is region-neutral' },
  ],
  'apps/studio/src/features/compliance/messages.ts': [
    { fragment: /French required when serving Québec|français requis pour servir le Québec/, reason: 'a fact about one named province’s language law, not the business’s place' },
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
      const hit = PLACE.exec(text)?.[0] ?? REGIONAL.exec(text)?.[0] ?? PROVINCE_KEY.exec(text)?.[0] ?? (CODE.test(text) ? text : undefined);
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
    expect(REGIONAL.test('Bilingual support 7 am – 11 pm MT')).toBe(true);
    expect(REGIONAL.test('Full AHS vetting.')).toBe(true);
    expect(REGIONAL.test('+1 (403) …')).toBe(true);
    expect(REGIONAL.test('Mountain bike · MTB')).toBe(false);
    expect(PROVINCE_KEY.test('alberta_corporate_access_number')).toBe(true);
    expect(PROVINCE_KEY.test("N° d'accès de société albertain")).toBe(true);
    expect(PROVINCE_KEY.test('corp_ab')).toBe(true);
    expect(PROVINCE_KEY.test('provincial_registry_number')).toBe(false);
    expect(PROVINCE_KEY.test('corp_prov')).toBe(false);
  });
});
