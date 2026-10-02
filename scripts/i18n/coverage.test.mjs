// S-116: the French coverage gate's own tests (node --test). `make i18n-check` runs these, then the gate.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  audit, catalogueFindings, categoryIds, compareCatalogues, docsTranslateIds, javaMap, jsxFindings, parseCsv,
  sameAsEnglishAllowed, templateWords, typescript, wordsOf,
} from './coverage.mjs';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

assert.ok(typescript(), 'run pnpm install in web/ first');

describe('catalogues in TypeScript', () => {
  it('finds missing, empty, English-in-French and extra keys, nested and spread', () => {
    const src = `
      const extra = { e1: 'Shared words' };
      const fr1 = { e1: 'Mots partagés' };
      export const useT = defineMessages({
        en: { title: 'Payouts', empty: 'Nothing yet', same: 'Save changes', brand: 'Northline', units: '{n} km', nested: { deep: 'Deep text' }, ...extra, missing: 'Only English' },
        fr: { title: 'Versements', empty: '', same: 'Save changes', brand: 'Northline', units: '{n} km', nested: { deep: 'Texte profond' }, ...fr1, stray: 'De trop' },
      });
      const pair = { en: 'Inline words', fr: 'Inline words' };`;
    const { strings, gaps } = catalogueFindings('fixture.ts', src);
    const kinds = Object.fromEntries(gaps.map((g) => [g.key.split('#')[1] ?? 'pair', g.kind]));
    assert.equal(kinds.empty, 'empty');
    assert.equal(kinds.same, 'same-as-english');
    assert.equal(kinds.missing, 'missing');
    assert.equal(kinds.stray, 'extra-in-french');
    assert.equal(kinds.pair, 'same-as-english');
    assert.equal(kinds.brand, undefined, 'a brand name is the same in French');
    assert.equal(kinds.units, undefined, 'placeholders and units are not words');
    assert.equal(kinds['nested.deep'], undefined);
    assert.equal(kinds.e1, undefined, 'spread members are compared too');
    assert.ok(strings.some((s) => s.key.endsWith('#nested.deep') && s.fr === 'Texte profond'));
  });

  it('compares plain objects (mobile catalogues, store fields, e-mail bundles)', () => {
    const { gaps } = compareCatalogues('x', { a: 'Hello there', b: 'Total', c: 'Gone' }, { a: 'Bonjour', b: 'Total', d: 'De trop' });
    assert.deepEqual(gaps.map((g) => `${g.key}:${g.kind}`).sort(), ['x#c:missing', 'x#d:extra-in-french']);
  });
});

describe('what counts as words', () => {
  it('drops placeholders, brands, units, identifiers and addresses', () => {
    assert.equal(wordsOf('{count, plural, one {# km} other {# km}}'), 'one other');
    assert.equal(wordsOf('Stripe Elements · Payment Element'), '');
    assert.equal(wordsOf('https://northline.ca hello@northline.ca shop.example.ca food.menus ahs_permit RT0001'), '');
    assert.equal(wordsOf('%d items in %s'), 'items in');
    assert.ok(sameAsEnglishAllowed('Total'));
    assert.ok(!sameAsEnglishAllowed('Save changes'));
  });
});

describe('text written straight into JSX', () => {
  it('flags text nodes and text attributes, not catalogue calls, Translate or symbols', () => {
    const src = `export const A = () => (
      <div title="Open the menu" className="x-y">
        Save your work
        {t('fine')}
        <Translate id="docs.x">Translated by Docusaurus</Translate>
        <input placeholder={'Search shops'} aria-label={t('label')} />
        {'Inline literal'}
        <span>· — 12 / 30 · {n} km</span>
      </div>)`;
    const found = jsxFindings('Fixture.tsx', src).map((f) => `${f.kind}:${f.en}`);
    assert.deepEqual(found.sort(), ['jsx-placeholder:Search shops', 'jsx-text:Inline literal', 'jsx-text:Save your work', 'jsx-title:Open the menu']);
  });

  it('reads the Docusaurus translation ids with their English', () => {
    const dir = mkdtempSync(join(tmpdir(), 'i18n-'));
    const file = join(dir, 'Page.tsx');
    writeFileSync(file, `const x = translate({ id: 'a.title', message: 'Title' }); export default () => <Translate id="a.body">Some body text</Translate>;`);
    assert.deepEqual([...docsTranslateIds([file])], [['a.title', 'Title'], ['a.body', 'Some body text']]);
  });
});

describe('server texts', () => {
  it('reads EN/FR maps of SMS and push wordings', () => {
    const src = `static final Map<String, String> EN = Map.ofEntries(Map.entry("a", "One " + "line"), Map.entry("b", "Two {0}"));
      static final Map<String, String> FR = Map.ofEntries(Map.entry("a", "Une ligne"));`;
    assert.deepEqual([...javaMap(src, 'EN')], [['a', 'One line'], ['b', 'Two {0}']]);
    assert.deepEqual([...javaMap(src, 'FR')], [['a', 'Une ligne']]);
  });

  it('sees only the words of an e-mail template that the bundle does not replace', () => {
    assert.equal(templateWords('<h1 th:text="#{x.heading}">Your payout</h1><p>Call us today</p>'), 'Call us today');
    assert.equal(templateWords('[(#{x.heading})]\n[# th:if="${a}"][(${b})][/]'), '');
  });
});

describe('category taxonomy', () => {
  it('derives the seeder ids (CategorySeeder.slug)', () => {
    const ids = new Map(categoryIds());
    assert.ok(ids.has('service.automotive.mobile-mechanic'));
    assert.ok([...ids.keys()].every((id) => /^[a-z]+(\.[a-z0-9-]+){1,2}$/.test(id)));
  });
});

describe('the review file', () => {
  it('parses quoted CSV fields with commas and quotes', () => {
    assert.deepEqual(parseCsv('k,s,f,st\n"a,b","say ""hi""",é,x\n'), [['k', 's', 'f', 'st'], ['a,b', 'say "hi"', 'é', 'x']]);
  });
});

describe('this repository', () => {
  const result = audit();
  it('has French for every customer-facing string (known gaps aside)', () => {
    assert.deepEqual(result.failing.map((g) => `[${g.surface}] ${g.kind} ${g.key}`), []);
  });

  it('checks every surface', () => {
    const counted = Object.fromEntries(result.surfaces.map((s) => [s.surface, s.strings]));
    for (const name of ['web-bundles', 'mobile', 'server', 'catalogue', 'store', 'legal', 'review']) assert.ok(counted[name] > 0, name);
    assert.ok(counted['web-bundles'] > 5000 && counted.mobile > 1000 && counted.server > 1000);
  });

  it('keeps the legal texts and the translator review as known gaps that a strict run fails on', () => {
    const strict = audit({ strict: true });
    assert.ok(strict.failing.some((g) => g.surface === 'legal'));
    assert.ok(strict.failing.some((g) => g.surface === 'review'));
  });
});
