// The legal document registry (S-106): every legal text Northline shows, its version, effective date and counsel's
// sign-off (who, when, the SHA-256 of the exact text they reviewed). registry.json is the record; this module computes
// the hashes and checks the record against the files. docs/compliance/legal/review-packet.md explains the process.
//
//   node registry.mjs status              every document: version, effective date, current hash, signed off or not
//   node registry.mjs hash <id>           the current hash of one document's text (what counsel signs)
//
// "Text" is what a reader sees: for an HTML page the visible text (tags, styles and scripts removed, entities decoded,
// whitespace collapsed), so a style change needs no new version and a changed word does; for a JSON file (the store
// privacy answers) the parsed JSON without "$comment" keys, canonically ordered.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const LEGAL_PACKAGE = fileURLToPath(new URL('./', import.meta.url));
export const REPO = join(LEGAL_PACKAGE, '../../../');
export const REGISTRY_FILE = join(LEGAL_PACKAGE, 'registry.json');

export const readRegistry = () => JSON.parse(readFileSync(REGISTRY_FILE, 'utf8'));

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', middot: '·', mdash: '—', ndash: '–', rsquo: '’', lsquo: '‘', rdquo: '”', ldquo: '“', hellip: '…', eacute: 'é', egrave: 'è', agrave: 'à', ccedil: 'ç' };

/** The visible text of an HTML page, whitespace collapsed. */
export function pageText(html) {
  return html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style|head)\b[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
      if (e[0] === '#') return String.fromCodePoint(e[1].toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : Number(e.slice(1)));
      return ENTITIES[e.toLowerCase()] ?? m;
    })
    .replace(/\s+/g, ' ')
    .trim();
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).filter(k => k !== '$comment').sort().map(k => [k, canonical(value[k])]));
  }
  return value;
}

/** The text a document's hash covers, read from its source (paths in registry.json are relative to the repo root). */
export function documentText(doc) {
  const raw = readFileSync(join(REPO, doc.source), 'utf8');
  if (doc.source.endsWith('.html')) return pageText(raw);
  if (doc.source.endsWith('.json')) return JSON.stringify(canonical(JSON.parse(raw)));
  throw new Error(`${doc.id}: no text rule for ${doc.source}`);
}

export const sha256 = text => createHash('sha256').update(text, 'utf8').digest('hex');

/** Hashed documents are pages and files; `code` documents are versioned where they live (their own tests pin them). */
export const isHashed = doc => doc.kind === 'page' || doc.kind === 'file';

export const currentVersion = doc => doc.versions[doc.versions.length - 1];

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** "Version 3.0 · Effective 1 October 2026" on a page → { version: '3.0', effective: '2026-10-01' }. */
export function pageVersionLine(text) {
  const m = /Version ([\d.]+) · Effective (\d{1,2}) ([A-Z][a-z]+) (\d{4})/.exec(text);
  if (!m) return null;
  const month = MONTHS.indexOf(m[3]) + 1;
  if (month === 0) return null;
  return { version: m[1], effective: `${m[4]}-${String(month).padStart(2, '0')}-${m[2].padStart(2, '0')}` };
}

/**
 * Every problem with the registry, as messages (empty = consistent):
 * - a hashed document whose text no longer matches its current version's hash (changed without a version bump);
 * - versions out of order, repeated, or two versions with the same text;
 * - a page whose "Version … · Effective …" line disagrees with the registry;
 * - a sign-off that is incomplete or covers a different text than its version's.
 */
export function registryProblems(registry = readRegistry()) {
  const problems = [];
  const ids = new Set();
  for (const doc of registry.documents) {
    if (ids.has(doc.id)) problems.push(`${doc.id}: duplicate id`);
    ids.add(doc.id);
    for (const field of ['title', 'kind', 'source', 'languages', 'appearsIn', 'versions']) {
      if (doc[field] === undefined || (Array.isArray(doc[field]) && doc[field].length === 0)) problems.push(`${doc.id}: ${field} is missing`);
    }
    if (!Array.isArray(doc.versions) || doc.versions.length === 0) continue;
    const seen = new Set();
    let previous = '';
    for (const v of doc.versions) {
      if (!v.version || !/^\d{4}-\d{2}-\d{2}$/.test(v.effective ?? '')) problems.push(`${doc.id}: every version needs "version" and an "effective" date (YYYY-MM-DD)`);
      if (seen.has(v.version)) problems.push(`${doc.id}: version ${v.version} is listed twice`);
      seen.add(v.version);
      if (v.effective < previous) problems.push(`${doc.id}: version ${v.version} is effective before the one listed above it`);
      previous = v.effective;
      if (isHashed(doc) && !/^[0-9a-f]{64}$/.test(v.sha256 ?? '')) problems.push(`${doc.id} ${v.version}: sha256 is missing`);
      if (v.signOff) {
        const s = v.signOff;
        if (!s.counsel || !/^\d{4}-\d{2}-\d{2}$/.test(s.date ?? '')) problems.push(`${doc.id} ${v.version}: a sign-off needs "counsel" and "date"`);
        if (isHashed(doc) && s.sha256 !== v.sha256) problems.push(`${doc.id} ${v.version}: counsel signed off a different text (sign-off hash ≠ version hash)`);
      }
    }
    if (isHashed(doc)) {
      const hashes = doc.versions.map(v => v.sha256);
      if (new Set(hashes).size !== hashes.length) problems.push(`${doc.id}: two versions have the same text`);
      const current = currentVersion(doc);
      const text = documentText(doc);
      const hash = sha256(text);
      if (hash !== current.sha256) {
        problems.push(`${doc.id}: the text changed without a version bump — add a version to registry.json (new version, effective date, sha256 ${hash})` +
          (doc.kind === 'page' ? ' and change the page’s "Version … · Effective …" line' : ''));
      }
      if (doc.kind === 'page') {
        const line = pageVersionLine(text);
        if (!line) problems.push(`${doc.id}: the page has no "Version … · Effective …" line`);
        else if (line.version !== current.version || line.effective !== current.effective) {
          problems.push(`${doc.id}: the page says version ${line.version} effective ${line.effective}, the registry ${current.version} effective ${current.effective}`);
        }
      }
    }
  }
  return problems;
}

function main(args) {
  const registry = readRegistry();
  const [command, id] = args;
  if (command === 'hash') {
    const doc = registry.documents.find(d => d.id === id);
    if (!doc || !isHashed(doc)) { console.error(`no hashed document "${id}"`); process.exit(2); }
    console.log(sha256(documentText(doc)));
    return;
  }
  if (command === 'status' || command === undefined) {
    for (const doc of registry.documents) {
      const v = currentVersion(doc);
      const signed = v.signOff ? `signed off by ${v.signOff.counsel} on ${v.signOff.date}` : 'NOT signed off';
      console.log(`${doc.id.padEnd(28)} v${v.version} effective ${v.effective} · ${doc.languages.join('/')} · ${signed}`);
    }
    const problems = registryProblems(registry);
    for (const p of problems) console.error(`problem: ${p}`);
    process.exit(problems.length ? 1 : 0);
  }
  console.error('usage: node registry.mjs status | hash <id>');
  process.exit(2);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main(process.argv.slice(2));
