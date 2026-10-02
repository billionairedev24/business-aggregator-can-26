#!/usr/bin/env node
// S-116 (Loi 96 readiness): finds every customer-facing string that has no fr-CA, across every surface Northline ships.
//
//   node scripts/i18n/coverage.mjs            the report, per surface; exit 1 when a surface has a gap
//   node scripts/i18n/coverage.mjs --json     the same as JSON (the test and CI read it)
//   node scripts/i18n/coverage.mjs --strict   known gaps fail too: run before opening any French-first place
//   make i18n-check                           this, plus its own tests (scripts/i18n/coverage.test.mjs)
//
// Surfaces (docs/runbooks/i18n.md explains each and how to fix a finding):
//   web-bundles   defineMessages/messagesFor catalogues and inline { en, fr } pairs in the consumer, Studio, console,
//                 docs site and shared packages: same keys, no empty French, no French equal to the English unless
//                 allow-listed (brand names, units, words spelt the same).
//   web-jsx       text written straight into JSX (text nodes, placeholder/title/aria-label/alt) outside a catalogue.
//   mobile        the consumer and courier apps' en / fr-CA catalogues (the same checks) and their JSX text.
//   server        the validation-message catalogue (docs/spec/validation-messages.fr-CA.tsv), every 404 resource
//                 (ProblemDetail detail) and HTTP status title, email templates (messages.properties vs _fr), the
//                 SMS / push wordings (EN/FR maps).
//   store         the App Store / Play listings: every en-CA field and file has its fr-CA counterpart.
//   legal         the legal texts (web/packages/legal): a French version of each — a known gap until counsel and a
//                 certified translator provide one (S-106 counsel question D1); reported, gated by allowlist.json.
//   review        docs/i18n/translation-review.csv: every row still matches its source (key and French).
//
// Region-neutral: nothing here names a province; which places need French first is region configuration
// (region.regions.french_first, S-116).
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const ALLOW = JSON.parse(readFileSync(join(ROOT, 'scripts/i18n/allowlist.json'), 'utf8'));

let tsModule;
/** The TypeScript compiler from web/ or mobile/ node_modules (both pin 5.x); null when neither is installed. */
export function typescript() {
  if (tsModule !== undefined) return tsModule;
  for (const base of ['web', 'mobile']) {
    try {
      tsModule = createRequire(join(ROOT, base, 'package.json'))('typescript');
      return tsModule;
    } catch { /* next */ }
  }
  tsModule = null;
  return tsModule;
}

// ---------------------------------------------------------------------------------------------------------------
// Helpers

const SKIP_DIRS = new Set(['node_modules', 'dist', 'build', '.output', '.tanstack', '.docusaurus', '__tests__', 'e2e', 'fixtures', 'coverage', 'storybook-static', '.expo', 'ios', 'android']);
const isTestFile = (f) => /\.(test|spec|stories)\.[cm]?[jt]sx?$/.test(f) || /[\\/](test|tests|__mocks__)[\\/]/.test(f);

export function walk(dir, exts, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (!SKIP_DIRS.has(name) && !name.startsWith('.')) walk(p, exts, out);
    } else if (exts.some((e) => name.endsWith(e)) && !name.endsWith('.d.ts') && !isTestFile(p)) out.push(p);
  }
  return out;
}

const rel = (p) => relative(ROOT, p).split(sep).join('/');

/** The letters left once placeholders, ICU arguments, URLs, e-mail addresses, brand names and units are removed. */
export function wordsOf(text) {
  let t = String(text);
  // ICU plural/select bodies keep their words; simple {name} and {n, number} arguments go
  t = t.replace(/\{\s*\w+\s*(,\s*(number|date|time)[^}]*)?\}/g, ' ');
  t = t.replace(/\{\s*\w+\s*,\s*(plural|select|selectordinal)\s*,/g, ' ');
  t = t.replace(/%(\d+\$)?[-0-9.]*[sdf]/g, ' ');
  t = t.replace(/https?:\/\/\S+/g, ' ').replace(/[\w.+-]+@[\w-]+(\.[\w-]+)+/g, ' ');
  // identifiers, not language: dotted names (domains, table.column), snake_case, tokens mixing letters and digits
  t = t.replace(/[\w-]+(\.[\w-]+)+/g, ' ').replace(/\b\w*_\w*\b/g, ' ').replace(/\b(?=\w*\d)(?=\w*[A-Za-z])\w+\b/g, ' ');
  const byLength = (a, b) => b.length - a.length; // "Stripe Elements" before "Stripe"
  const brands = [...ALLOW.brands].sort(byLength).map((b) => b.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
  t = t.replace(new RegExp(`(?<![\\p{L}])(${brands})(?![\\p{L}])`, 'gu'), ' ');
  const units = [...ALLOW.units].sort(byLength).map((u) => u.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|');
  t = t.replace(new RegExp(`(?<![\\p{L}])(${units})(?![\\p{L}])`, 'gu'), ' ');
  return (t.match(/\p{L}{2,}/gu) ?? []).join(' ');
}

/** A French value equal to its English is a gap unless it carries no translatable words or is allow-listed. */
export function sameAsEnglishAllowed(value) {
  const v = String(value).trim();
  if (ALLOW.sameInFrench.includes(v)) return true;
  return wordsOf(v) === '';
}

// ---------------------------------------------------------------------------------------------------------------
// Catalogues in TypeScript (web): object literals with an `en` and a `fr` member

function propName(ts, name) {
  if (!name) return undefined;
  if (ts.isIdentifier(name) || ts.isStringLiteral(name) || ts.isNumericLiteral(name) || ts.isNoSubstitutionTemplateLiteral(name)) return name.text;
  return undefined;
}

function stringValue(ts, node) {
  if (!node) return undefined;
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isSatisfiesExpression?.(node)) return stringValue(ts, node.expression);
  return undefined;
}

function unwrap(ts, node) {
  while (node && (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || (ts.isSatisfiesExpression && ts.isSatisfiesExpression(node)))) node = node.expression;
  return node;
}

/** Flattens an object literal to dotted keys → string (or null for a value that is not a literal string). */
function flatten(ts, obj, locals, prefix = '', out = new Map()) {
  for (const p of obj.properties) {
    if (ts.isSpreadAssignment(p)) {
      const target = ts.isIdentifier(unwrap(ts, p.expression)) ? locals.get(unwrap(ts, p.expression).text) : undefined;
      if (target) flatten(ts, target, locals, prefix, out);
      continue;
    }
    let key; let init;
    if (ts.isPropertyAssignment(p)) { key = propName(ts, p.name); init = unwrap(ts, p.initializer); }
    else if (ts.isShorthandPropertyAssignment(p)) { key = p.name.text; init = locals.get(p.name.text); }
    else continue;
    if (key === undefined) continue;
    const full = prefix ? `${prefix}.${key}` : key;
    if (init && ts.isObjectLiteralExpression(init)) flatten(ts, init, locals, full, out);
    else out.set(full, stringValue(ts, init) ?? null);
  }
  return out;
}

function localObjects(ts, sf) {
  const locals = new Map();
  const visit = (node) => {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      const init = unwrap(ts, node.initializer);
      if (init && ts.isObjectLiteralExpression(init)) locals.set(node.name.text, init);
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return locals;
}

const FR_KEYS = new Set(['fr', 'fr-CA', 'frCA']);
const EN_KEYS = new Set(['en', 'en-CA', 'enCA']);

/** Every en/fr catalogue and inline pair in one TypeScript source. */
export function catalogueFindings(file, source = readFileSync(file, 'utf8')) {
  const ts = typescript();
  const sf = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const locals = localObjects(ts, sf);
  const strings = []; // { key, en, fr }
  const gaps = []; // { key, kind, en, fr }
  const visit = (node) => {
    if (ts.isObjectLiteralExpression(node)) {
      let en; let fr;
      for (const p of node.properties) {
        const name = ts.isPropertyAssignment(p) || ts.isShorthandPropertyAssignment(p) ? propName(ts, p.name) : undefined;
        if (name === undefined) continue;
        const init = ts.isPropertyAssignment(p) ? unwrap(ts, p.initializer) : locals.get(name) ?? p.name;
        if (EN_KEYS.has(name)) en = init; else if (FR_KEYS.has(name)) fr = init;
      }
      if (en && fr) {
        if (ts.isIdentifier(en)) en = locals.get(en.text) ?? en;
        if (ts.isIdentifier(fr)) fr = locals.get(fr.text) ?? fr;
        if (ts.isObjectLiteralExpression(en) && ts.isObjectLiteralExpression(fr)) {
          const line = sf.getLineAndCharacterOfPosition(node.getStart()).line + 1;
          const e = flatten(ts, en, locals); const f = flatten(ts, fr, locals);
          for (const [k, v] of e) {
            if (v === null) continue;
            const key = `${rel(file)}:${line}#${k}`;
            const fv = f.get(k);
            strings.push({ key, en: v, fr: fv ?? null });
            if (!f.has(k)) gaps.push({ key, kind: 'missing', en: v });
            else if (fv === null) continue;
            else if (fv.trim() === '' && v.trim() !== '') gaps.push({ key, kind: 'empty', en: v, fr: fv });
            else if (fv === v && !sameAsEnglishAllowed(v)) gaps.push({ key, kind: 'same-as-english', en: v, fr: fv });
          }
          for (const k of f.keys()) if (!e.has(k)) gaps.push({ key: `${rel(file)}:${line}#${k}`, kind: 'extra-in-french', fr: f.get(k) });
          return; // nested pairs inside a catalogue are its own keys
        }
        const ev = stringValue(ts, en); const fv = stringValue(ts, fr);
        if (ev !== undefined) {
          const line = sf.getLineAndCharacterOfPosition(node.getStart()).line + 1;
          const key = `${rel(file)}:${line}`;
          strings.push({ key, en: ev, fr: fv ?? null });
          if (fv !== undefined) {
            if (fv.trim() === '' && ev.trim() !== '') gaps.push({ key, kind: 'empty', en: ev, fr: fv });
            else if (fv === ev && !sameAsEnglishAllowed(ev)) gaps.push({ key, kind: 'same-as-english', en: ev, fr: fv });
          }
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return { strings, gaps };
}

// ---------------------------------------------------------------------------------------------------------------
// Text written straight into JSX

const TEXT_ATTRIBUTES = new Set(['placeholder', 'title', 'aria-label', 'alt', 'label', 'aria-description', 'accessibilityLabel', 'accessibilityHint']);

/** Text nodes and text attributes with words in them, outside any catalogue. */
export function jsxFindings(file, source = readFileSync(file, 'utf8')) {
  const ts = typescript();
  const sf = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const out = [];
  const allowedHere = ALLOW.jsx[rel(file)] ?? [];
  const add = (node, text, where) => {
    const t = text.replace(/\s+/g, ' ').trim();
    if (!t || wordsOf(t) === '' || ALLOW.sameInFrench.includes(t) || allowedHere.includes(t)) return;
    out.push({ key: `${rel(file)}:${sf.getLineAndCharacterOfPosition(node.getStart()).line + 1}`, kind: where, en: t });
  };
  const visit = (node) => {
    // Docusaurus' <Translate id="…">English</Translate>: the English is the default, i18n/fr/code.json the French
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText(sf) === 'Translate') return;
    if (ts.isJsxText(node)) add(node, node.text, 'jsx-text');
    else if (ts.isJsxAttribute(node) && TEXT_ATTRIBUTES.has(node.name.getText(sf)) && node.initializer) {
      const v = ts.isStringLiteral(node.initializer) ? node.initializer.text
        : ts.isJsxExpression(node.initializer) && node.initializer.expression ? stringValue(ts, node.initializer.expression) : undefined;
      if (v !== undefined) add(node, v, `jsx-${node.name.getText(sf)}`);
    } else if (ts.isJsxExpression(node) && node.expression && node.parent && (ts.isJsxElement(node.parent) || ts.isJsxFragment(node.parent))) {
      const v = stringValue(ts, node.expression);
      if (v !== undefined) add(node, v, 'jsx-text');
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return out;
}

// ---------------------------------------------------------------------------------------------------------------
// Mobile catalogues: modules exporting the en and fr-CA objects (evaluated, so spreads across files resolve)

/** Evaluates a TypeScript module that only imports relative modules and types (the mobile catalogues). */
export function loadTsModule(file, cache = new Map()) {
  if (cache.has(file)) return cache.get(file);
  const ts = typescript();
  const js = ts.transpileModule(readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  cache.set(file, module.exports);
  const req = (spec) => {
    if (!spec.startsWith('.')) return {};
    const base = join(dirname(file), spec);
    const target = [`${base}.ts`, `${base}.tsx`, join(base, 'index.ts')].find(existsSync);
    if (!target) throw new Error(`${rel(file)}: cannot resolve ${spec}`);
    return loadTsModule(target, cache);
  };
  vm.runInNewContext(js, { module, exports: module.exports, require: req });
  cache.set(file, module.exports);
  return module.exports;
}

export function compareCatalogues(label, en, fr) {
  const strings = []; const gaps = [];
  for (const [k, v] of Object.entries(en)) {
    if (typeof v !== 'string') continue;
    const key = `${label}#${k}`; const fv = fr[k];
    strings.push({ key, en: v, fr: fv ?? null });
    if (fv === undefined) gaps.push({ key, kind: 'missing', en: v });
    else if (String(fv).trim() === '' && v.trim() !== '') gaps.push({ key, kind: 'empty', en: v, fr: fv });
    else if (fv === v && !sameAsEnglishAllowed(v)) gaps.push({ key, kind: 'same-as-english', en: v, fr: fv });
  }
  for (const k of Object.keys(fr)) if (!(k in en)) gaps.push({ key: `${label}#${k}`, kind: 'extra-in-french', fr: fr[k] });
  return { strings, gaps };
}

// ---------------------------------------------------------------------------------------------------------------
// Surfaces

function surface(name, strings, gaps, notes = []) {
  const keys = new Set(strings.map((s) => s.key));
  const covered = strings.length - new Set(gaps.filter((g) => g.kind !== 'extra-in-french' && keys.has(g.key)).map((g) => g.key)).size;
  const result = { surface: name, strings: strings.length, covered, gaps, notes };
  Object.defineProperty(result, 'list', { value: strings, enumerable: false }); // for the review check, not printed
  return result;
}

const WEB_ROOTS = ['web/apps/consumer/src', 'web/apps/studio/src', 'web/apps/console/src', 'web/apps/docs/src', 'web/packages/ui/src', 'web/packages/auth-kit/src', 'web/packages/client/src'];

export function webBundles() {
  const strings = []; const gaps = [];
  for (const r of WEB_ROOTS) for (const f of walk(join(ROOT, r), ['.ts', '.tsx'])) {
    const res = catalogueFindings(f);
    strings.push(...res.strings); gaps.push(...res.gaps);
  }
  // the docs site's own French: <Translate id> / translate({ id }) texts against i18n/fr/code.json, and the public guides
  const docs = join(ROOT, 'web/apps/docs');
  const code = JSON.parse(readFileSync(join(docs, 'i18n/fr/code.json'), 'utf8'));
  for (const [id, en] of docsTranslateIds(walk(join(docs, 'src'), ['.tsx', '.ts']))) {
    const fr = code[id]?.message;
    const key = `web/apps/docs/i18n/fr/code.json#${id}`;
    strings.push({ key, en, fr: fr ?? null });
    if (fr === undefined) gaps.push({ key, kind: 'missing', en });
    else if (!fr.trim()) gaps.push({ key, kind: 'empty', en });
    else if (fr === en && !sameAsEnglishAllowed(en)) gaps.push({ key, kind: 'same-as-english', en });
  }
  const guides = walk(join(docs, 'guides'), ['.md', '.mdx']);
  for (const g of guides) {
    const fr = join(docs, 'i18n/fr/docusaurus-plugin-content-docs/current', relative(join(docs, 'guides'), g));
    const key = rel(g);
    strings.push({ key, en: key, fr: existsSync(fr) ? rel(fr) : null });
    if (!existsSync(fr)) gaps.push({ key, kind: 'missing', en: 'public guide page without a French translation' });
  }
  return surface('web-bundles', strings, gaps);
}

/** Docusaurus translation ids with their English default: <Translate id="x">English</Translate>, translate({ id, message }). */
export function docsTranslateIds(files) {
  const ts = typescript();
  const out = new Map();
  for (const file of files) {
    const sf = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    const visit = (node) => {
      if (ts.isJsxElement(node) && node.openingElement.tagName.getText(sf) === 'Translate') {
        const idAttr = node.openingElement.attributes.properties.find((a) => ts.isJsxAttribute(a) && a.name.getText(sf) === 'id');
        const id = idAttr?.initializer && ts.isStringLiteral(idAttr.initializer) ? idAttr.initializer.text : undefined;
        const text = node.children.map((c) => (ts.isJsxText(c) ? c.text : '')).join('').replace(/\s+/g, ' ').trim();
        if (id) out.set(id, text);
      } else if (ts.isCallExpression(node) && node.expression.getText(sf) === 'translate' && node.arguments[0] && ts.isObjectLiteralExpression(node.arguments[0])) {
        const props = new Map(node.arguments[0].properties.filter(ts.isPropertyAssignment).map((p) => [propName(ts, p.name), stringValue(ts, p.initializer)]));
        if (props.get('id')) out.set(props.get('id'), props.get('message') ?? '');
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  }
  return out;
}

export function webJsx() {
  const strings = []; const gaps = [];
  for (const r of WEB_ROOTS) for (const f of walk(join(ROOT, r), ['.tsx'])) {
    const found = jsxFindings(f);
    gaps.push(...found);
  }
  // denominator: JSX text sites = catalogue strings used + hardcoded ones; report hardcoded as gaps only
  return { surface: 'web-jsx', strings: null, covered: null, gaps, notes: ['hard-coded JSX text and text attributes outside a catalogue (each one is a gap)'] };
}

const MOBILE_APPS = [
  { app: 'consumer', en: 'mobile/apps/consumer/src/i18n/en/index.ts', enExport: 'en', fr: 'mobile/apps/consumer/src/i18n/fr-CA/index.ts', frExport: 'frCA' },
  { app: 'courier', en: 'mobile/apps/courier/src/i18n/en.ts', enExport: 'en', fr: 'mobile/apps/courier/src/i18n/fr-CA.ts', frExport: 'frCA' },
];
const MOBILE_JSX_ROOTS = ['mobile/apps/consumer/app', 'mobile/apps/consumer/src', 'mobile/apps/courier/app', 'mobile/apps/courier/src', 'mobile/packages/mobile-kit/src'];

export function mobile() {
  const strings = []; const gaps = [];
  for (const m of MOBILE_APPS) {
    const en = loadTsModule(join(ROOT, m.en))[m.enExport];
    const fr = loadTsModule(join(ROOT, m.fr))[m.frExport];
    const res = compareCatalogues(`mobile/${m.app}`, en, fr);
    strings.push(...res.strings); gaps.push(...res.gaps);
    // iOS/Android system strings (InfoPlist, permission prompts): locales/en.json vs fr.json
    const loc = join(ROOT, 'mobile/apps', m.app, 'locales');
    if (existsSync(join(loc, 'en.json')) || existsSync(join(loc, 'fr.json'))) {
      const e = existsSync(join(loc, 'en.json')) ? JSON.parse(readFileSync(join(loc, 'en.json'), 'utf8')) : {};
      const f = existsSync(join(loc, 'fr.json')) ? JSON.parse(readFileSync(join(loc, 'fr.json'), 'utf8')) : {};
      const r2 = compareCatalogues(`mobile/${m.app}/locales`, e, f);
      strings.push(...r2.strings); gaps.push(...r2.gaps.filter((g) => g.kind !== 'extra-in-french' || Object.keys(e).length > 0));
    }
  }
  for (const r of MOBILE_JSX_ROOTS) for (const f of walk(join(ROOT, r), ['.tsx'])) {
    const res = catalogueFindings(f);
    strings.push(...res.strings); gaps.push(...res.gaps);
    gaps.push(...jsxFindings(f));
  }
  return surface('mobile', strings, gaps);
}

// --- server

export function readTsv(path) {
  const rows = [];
  for (const [i, line] of readFileSync(path, 'utf8').split('\n').entries()) {
    if (!line.trim() || line.startsWith('#')) continue;
    const [en, fr, status] = line.split('\t');
    rows.push({ line: i + 1, en, fr, status });
  }
  return rows;
}

function readProperties(path) {
  const out = new Map();
  for (const raw of readFileSync(path, 'utf8').split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('!')) continue;
    const i = line.search(/[=:]/);
    if (i < 0) continue;
    out.set(line.slice(0, i).trim(), line.slice(i + 1).trim());
  }
  return out;
}

/** `static final Map<String, String> EN = Map.ofEntries(Map.entry("k", "v" + "w"), …)` → Map. */
export function javaMap(source, name) {
  const start = source.search(new RegExp(`Map<String,\\s*String>\\s+${name}\\s*=`));
  if (start < 0) return null;
  let depth = 0; let i = source.indexOf('(', start); const begin = i;
  for (; i < source.length; i++) {
    if (source[i] === '"') { i++; while (source[i] !== '"') { if (source[i] === '\\') i++; i++; } continue; }
    if (source[i] === '(') depth++;
    else if (source[i] === ')' && --depth === 0) break;
  }
  const body = source.slice(begin, i);
  const out = new Map();
  const unquote = (chunk) => [...chunk.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => JSON.parse(`"${m[1]}"`)).join('');
  for (const m of body.matchAll(/Map\.entry\(\s*"((?:[^"\\]|\\.)*)"\s*,([\s\S]*?)\)(?=\s*(?:[,)]|$))/g)) out.set(m[1], unquote(m[2]));
  return out;
}

const SERVER_MAPS = [
  'server/worker/src/main/java/ca/northline/worker/notifications/Notices.java',
  'server/worker/src/main/java/ca/northline/worker/notifications/PersonalNotices.java',
];

/** Resource names of `new NotFound("…", id)`: each is the 404 ProblemDetail's detail ("No order with id …"). */
export function notFoundResources() {
  const out = new Set();
  for (const f of walk(join(ROOT, 'server'), ['.java'])) {
    if (!f.includes(`${sep}src${sep}main${sep}`)) continue;
    for (const m of readFileSync(f, 'utf8').matchAll(/new NotFound\(\s*"([^"]+)"/g)) out.add(m[1]);
  }
  return [...out].sort();
}

/** Spring's HttpStatus reason phrases of 4xx/5xx (the ProblemDetail title when none is set), 418 aside. */
export const HTTP_TITLES = ['Bad Request', 'Unauthorized', 'Payment Required', 'Forbidden', 'Not Found', 'Method Not Allowed', 'Not Acceptable', 'Proxy Authentication Required', 'Request Timeout', 'Conflict', 'Gone', 'Length Required', 'Precondition Failed', 'Content Too Large', 'Payload Too Large', 'URI Too Long', 'Unsupported Media Type', 'Requested range not satisfiable', 'Expectation Failed', 'Misdirected Request', 'Unprocessable Content', 'Unprocessable Entity', 'Locked', 'Failed Dependency', 'Too Early', 'Upgrade Required', 'Precondition Required', 'Too Many Requests', 'Request Header Fields Too Large', 'Unavailable For Legal Reasons', 'Internal Server Error', 'Not Implemented', 'Bad Gateway', 'Service Unavailable', 'Gateway Timeout', 'HTTP Version not supported', 'Variant Also Negotiates', 'Insufficient Storage', 'Loop Detected', 'Bandwidth Limit Exceeded', 'Not Extended', 'Network Authentication Required'];

/**
 * The words of an email template that come from neither the message bundle nor the model: a template's text is
 * `th:text="#{…}"` (its own text is a preview the bundle replaces) or `[(#{…})]` in the plain-text templates.
 */
export function templateWords(body) {
  const t = body
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/(<(\w+)\b[^>]*\bth:(?:u?text|replace)=[^>]*>)[^<]*/g, '$1') // previews replaced at render time
    .replace(/<[^>]+>/g, ' ')
    .replace(/\[#[^\]]*\]|\[\/\]|\[\([\s\S]*?\)\]|\[\[[\s\S]*?\]\]/g, ' ')
    .replace(/&[a-z]+;|&#\d+;/g, ' ');
  return wordsOf(t);
}

export function server() {
  const strings = []; const gaps = []; const notes = [];
  const tsvPath = join(ROOT, 'docs/spec/validation-messages.fr-CA.tsv');
  const rows = readTsv(tsvPath);
  const statuses = {};
  const english = new Set();
  for (const r of rows) {
    const key = `validation-messages.fr-CA.tsv:${r.line}`;
    strings.push({ key, en: r.en, fr: r.fr });
    english.add(r.en);
    statuses[r.status] = (statuses[r.status] ?? 0) + 1;
    if (!r.fr || !r.fr.trim()) gaps.push({ key, kind: 'empty', en: r.en });
    else if (r.fr === r.en && !sameAsEnglishAllowed(r.en)) gaps.push({ key, kind: 'same-as-english', en: r.en });
    if (!['shipped', 'new', 'review'].includes(r.status)) gaps.push({ key, kind: 'bad-status', en: r.en, fr: r.status });
  }
  notes.push(`validation catalogue statuses: ${Object.entries(statuses).map(([k, v]) => `${k} ${v}`).join(', ')}`);
  // 404 details and status titles go through the same catalogue (ApiExceptionHandler, S-116)
  for (const resource of notFoundResources()) {
    const en = `No ${resource} with id %s`;
    const key = `NotFound:${resource}`;
    strings.push({ key, en, fr: english.has(en) ? 'catalogue' : null });
    if (!english.has(en)) gaps.push({ key, kind: 'missing', en });
  }
  for (const title of HTTP_TITLES) {
    const key = `ProblemDetail.title:${title}`;
    strings.push({ key, en: title, fr: english.has(title) ? 'catalogue' : null });
    if (!english.has(title)) gaps.push({ key, kind: 'missing', en: title });
  }
  // email templates
  const emailDir = join(ROOT, 'server/email/src/main/resources/email');
  const en = readProperties(join(emailDir, 'messages.properties'));
  const fr = readProperties(join(emailDir, 'messages_fr.properties'));
  const r = compareCatalogues('email/messages', Object.fromEntries(en), Object.fromEntries(fr));
  strings.push(...r.strings); gaps.push(...r.gaps);
  const templates = readdirSync(join(emailDir, 'templates'));
  for (const t of templates) {
    const body = readFileSync(join(emailDir, 'templates', t), 'utf8');
    const words = templateWords(body);
    if (words && !ALLOW.emailTemplateWords.includes(t)) gaps.push({ key: `email/templates/${t}`, kind: 'literal-text', en: words.slice(0, 80) });
  }
  // SMS / push wordings
  for (const file of SERVER_MAPS) {
    const src = readFileSync(join(ROOT, file), 'utf8');
    const e = javaMap(src, 'EN'); const f = javaMap(src, 'FR');
    if (!e || !f) { gaps.push({ key: file, kind: 'missing', en: 'EN/FR maps not found' }); continue; }
    const res = compareCatalogues(file.split('/').pop(), Object.fromEntries(e), Object.fromEntries(f));
    strings.push(...res.strings); gaps.push(...res.gaps);
  }
  return surface('server', strings, gaps, notes);
}

// --- the category taxonomy (db/seed/categories.json is English; French names are catalogue.category_labels rows)

const slug = (name) => name.toLowerCase().replaceAll('&', 'and').replaceAll('é', 'e').replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');

/** Every category id the seeder creates (CategorySeeder.slug), with its English name. */
export function categoryIds() {
  const seed = JSON.parse(readFileSync(join(ROOT, 'db/seed/categories.json'), 'utf8'));
  const out = [];
  for (const [root, groups] of Object.entries(seed)) {
    if (root.startsWith('$')) continue;
    for (const g of groups) {
      const group = `${root}.${slug(g.group)}`;
      out.push([group, g.group]);
      for (const item of g.items) out.push([`${group}.${slug(item[0])}`, item[0]]);
    }
  }
  return out;
}

export function catalogue() {
  const strings = []; const gaps = [];
  const french = new Map();
  for (const f of readdirSync(join(ROOT, 'db/migrations')).filter((n) => n.endsWith('.sql')).sort()) {
    const sql = readFileSync(join(ROOT, 'db/migrations', f), 'utf8');
    if (!sql.includes('category_labels')) continue;
    for (const m of sql.matchAll(/\(\s*'([^']+)'\s*,\s*'fr'\s*,\s*'((?:[^']|'')+)'\s*\)/g)) french.set(m[1], m[2].replaceAll("''", "'"));
  }
  for (const [id, en] of categoryIds()) {
    const fr = french.get(id);
    const key = `catalogue.category_labels:${id}`;
    strings.push({ key, en, fr: fr ?? null });
    if (!fr) gaps.push({ key, kind: 'missing', en });
    else if (fr === en && !sameAsEnglishAllowed(en)) gaps.push({ key, kind: 'same-as-english', en });
  }
  return surface('catalogue', strings, gaps, ['merchant listing text is the merchant\'s own: French-first places require it at publish (region french_listings)']);
}

// --- store listings

export function store() {
  const strings = []; const gaps = [];
  for (const app of ['consumer', 'courier']) {
    const dir = join(ROOT, 'mobile/apps', app, 'store');
    const cfg = JSON.parse(readFileSync(join(dir, 'store.config.json'), 'utf8'));
    const info = cfg.apple?.info ?? {};
    const e = info['en-CA'] ?? {}; const f = info['fr-CA'] ?? {};
    const res = compareCatalogues(`store/${app}/apple`, e, f);
    strings.push(...res.strings); gaps.push(...res.gaps);
    const play = join(dir, 'play');
    for (const file of walk(join(play, 'en-CA'), ['.txt'])) {
      const other = file.replace(`${sep}en-CA${sep}`, `${sep}fr-CA${sep}`);
      const key = rel(file);
      const enText = readFileSync(file, 'utf8').trim();
      const frText = existsSync(other) ? readFileSync(other, 'utf8').trim() : null;
      strings.push({ key, en: enText, fr: frText });
      if (frText === null) gaps.push({ key, kind: 'missing', en: enText.slice(0, 80) });
      else if (!frText) gaps.push({ key, kind: 'empty', en: enText.slice(0, 80) });
      else if (frText === enText && !sameAsEnglishAllowed(enText)) gaps.push({ key, kind: 'same-as-english', en: enText.slice(0, 80) });
    }
  }
  return surface('store', strings, gaps);
}

// --- legal texts

export function legal() {
  const strings = []; const gaps = []; const notes = [];
  const dir = join(ROOT, 'web/packages/legal');
  const registryPath = join(dir, 'registry.json');
  const docs = existsSync(registryPath)
    ? JSON.parse(readFileSync(registryPath, 'utf8')).documents.map((d) => ({ id: d.id, languages: d.languages ?? ['en'], source: d.source }))
    : readdirSync(join(dir, 'pages')).filter((p) => p.endsWith('.html') && !/\.fr(-CA)?\.html$/.test(p)).map((p) => ({ id: p.replace(/\.html$/, ''), source: `web/packages/legal/pages/${p}`, languages: existsSync(join(dir, 'pages', p.replace(/\.html$/, '.fr-CA.html'))) ? ['en', 'fr'] : ['en'] }));
  for (const d of docs) {
    const key = `legal:${d.id}`;
    if (ALLOW.legalNotCustomerFacing?.[d.id]) {
      notes.push(`${d.id}: not shown to customers as text — ${ALLOW.legalNotCustomerFacing[d.id]}`);
      continue;
    }
    const french = d.languages.some((l) => String(l).startsWith('fr'));
    strings.push({ key, en: d.source, fr: french ? 'fr' : null });
    if (!french) {
      const known = ALLOW.legalKnownGaps[d.id];
      gaps.push({ key, kind: known ? 'known-gap' : 'missing', en: d.source, fr: known });
    }
  }
  notes.push('legal texts need counsel and a certified translator (counsel question D1, docs/compliance/legal/counsel-questions.md); known gaps are reported, not failed');
  return surface('legal', strings, gaps, notes);
}

// --- the translation review file

export function parseCsv(text) {
  const rows = []; let row = []; let field = ''; let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field || row.length) { row.push(field); rows.push(row); }
  return rows;
}

export const REVIEW_STATUSES = ['needs translator review', 'reviewed'];

/** Every row of docs/i18n/translation-review.csv names a string that exists with that French. */
export function review(all) {
  const path = join(ROOT, 'docs/i18n/translation-review.csv');
  const strings = []; const gaps = [];
  if (!existsSync(path)) return surface('review', strings, [{ key: 'docs/i18n/translation-review.csv', kind: 'missing', en: 'file' }]);
  const [header, ...rows] = parseCsv(readFileSync(path, 'utf8')).filter((r) => r.length > 1);
  if (header.join(',') !== 'key,source,french,status') gaps.push({ key: 'header', kind: 'bad-header', en: header.join(',') });
  const frenchBySource = new Map();
  for (const s of all) if (s.fr) {
    const list = frenchBySource.get(s.en) ?? new Set();
    list.add(s.fr); frenchBySource.set(s.en, list);
  }
  for (const [key, source, french, status] of rows) {
    strings.push({ key, en: source, fr: french });
    if (!REVIEW_STATUSES.includes(status)) gaps.push({ key, kind: 'bad-status', en: source, fr: status });
    const known = frenchBySource.get(source);
    if (!known || !known.has(french)) gaps.push({ key, kind: 'stale-review-row', en: source, fr: french });
  }
  const pending = rows.filter((r) => r[3] === 'needs translator review').length;
  if (pending) {
    gaps.push({ key: 'docs/i18n/translation-review.csv', kind: 'known-gap', en: `${pending} rows`, fr: `${pending} rows of French written without a certified translator await review (and every other French string of the product: none has been reviewed yet). Blocks a French-first launch.` });
  }
  return surface('review', strings, gaps, [`${pending} of ${rows.length} rows need a certified translator's review`]);
}

/** Every surface; `failing` = gaps that fail the gate (known legal gaps don't, unless `strict`). */
export function audit({ strict = false } = {}) {
  const surfaces = [webBundles(), webJsx(), mobile(), server(), catalogue(), store(), legal()];
  const all = surfaces.flatMap((s) => s.list ?? []);
  surfaces.push(review(all));
  const failing = surfaces.flatMap((s) => s.gaps.filter((g) => strict || g.kind !== 'known-gap').map((g) => ({ surface: s.surface, ...g })));
  return { surfaces, failing };
}

function print({ surfaces, failing }) {
  const pad = (s, n) => String(s).padEnd(n);
  console.log(`${pad('surface', 13)}${pad('strings', 9)}${pad('with fr', 9)}gaps`);
  for (const s of surfaces) {
    const known = s.gaps.filter((g) => g.kind === 'known-gap').length;
    const counted = s.strings === null;
    console.log(`${pad(s.surface, 13)}${pad(counted ? '-' : s.strings, 9)}${pad(counted ? '-' : s.covered, 9)}${s.gaps.length - known}${known ? ` (+${known} known)` : ''}`);
    for (const n of s.notes) console.log(`             · ${n}`);
  }
  const known = surfaces.flatMap((s) => s.gaps.filter((g) => g.kind === 'known-gap').map((g) => ({ surface: s.surface, ...g })));
  for (const g of known) if (!failing.includes(g)) console.log(`\nKnown gap [${g.surface}] ${g.key}: ${g.fr}`);
  if (failing.length) {
    console.log('\nGaps:');
    for (const g of failing) console.log(`  [${g.surface}] ${g.kind} ${g.key}${g.en ? ` — ${JSON.stringify(g.en).slice(0, 100)}` : ''}`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  if (!typescript()) {
    console.error('i18n-check needs TypeScript: run pnpm install in web/ (or mobile/) first.');
    process.exit(2);
  }
  const result = audit({ strict: process.argv.includes('--strict') });
  if (process.argv.includes('--json')) console.log(JSON.stringify(result, null, 2));
  else print(result);
  process.exit(result.failing.length ? 1 : 0);
}
