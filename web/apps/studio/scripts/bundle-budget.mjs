// S-69 bundle budget, run after `vite build`: the initial JS — the entry script in dist/index.html, the chunks it
// preloads (<link rel="modulepreload">), and every chunk those import statically — must stay under 250 kB gzip. Fails the build otherwise and names the biggest files.
// Usage: node scripts/bundle-budget.mjs [dist] [budget kB]
import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';

const dist = process.argv[2] ?? join(import.meta.dirname, '..', 'dist');
const budgetKb = Number(process.argv[3] ?? 250);
const html = readFileSync(join(dist, 'index.html'), 'utf8');
const entry = /<script type="module"[^>]*src="\/assets\/([^"]+\.js)"/.exec(html)?.[1];
if (!entry) throw new Error(`No module entry script in ${dist}/index.html`);

const files = new Set();
const walk = file => {
  if (files.has(file)) return;
  files.add(file);
  const source = readFileSync(join(dist, 'assets', file), 'utf8');
  // static imports and re-exports of sibling chunks; dynamic import() is lazy and not counted
  for (const m of source.matchAll(/(?:import|export)\s*(?:[^'"()]*?from\s*)?["']\.\/([^"']+\.js)["']/g)) walk(m[1]);
};
walk(entry);
for (const m of html.matchAll(/<link rel="modulepreload"[^>]*href="\/assets\/([^"]+\.js)"/g)) walk(m[1]);

const rows = [...files].map(f => ({ f, gz: gzipSync(readFileSync(join(dist, 'assets', f)), { level: 9 }).length })).sort((a, b) => b.gz - a.gz);
const total = rows.reduce((n, r) => n + r.gz, 0) / 1000;
const line = `initial JS ${total.toFixed(1)} kB gzip in ${rows.length} file(s) (budget ${budgetKb} kB)`;
if (total > budgetKb) {
  console.error(`✗ ${line}`);
  for (const r of rows.slice(0, 5)) console.error(`  ${r.f}  ${(r.gz / 1000).toFixed(1)} kB`);
  console.error('Split it (lazy routes, dynamic import) or raise the budget with a reason in DECISIONS.md.');
  process.exit(1);
}
console.log(`✓ ${line}`);
