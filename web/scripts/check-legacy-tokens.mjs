#!/usr/bin/env node
// Lint (redesign 2026-10-18): the shared components and the three app shells use the semantic tokens only
// (--color-action-primary, --color-text-muted, …; docs/design/redesign-spec.md § 5). The "Spruce & Honey" names
// (--color-accent*, --color-highlight*, --color-neutral-*, --color-bg, --font-body, …) are deprecated aliases kept in
// dist/tokens.css for the screens later phases rebuild; this keeps them out of what is already rebuilt. Add a
// directory here once its screens are rebuilt.
//
//   node scripts/check-legacy-tokens.mjs        (from web/, or `pnpm lint:tokens`)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const roots = ['packages/ui/src', 'apps/consumer/src/features/shell',
  // redesign phase 1: the consumer demo path (§ 4)
  'apps/consumer/src/features/home', 'apps/consumer/src/features/search',
  'apps/studio/src/features/shell', 'apps/console/src/features/shell', 'apps/studio/src/studio.css', 'apps/console/src/console.css'];
const LEGACY = /var\(\s*--(?:color-(?:accent(?:-2)?(?:-\d{3})?|on-accent|highlight(?:-\d{3})?|on-highlight|process-yellow|bg|surface-2|divider|neutral-\d{3}|ink)|font-body|font-heading-weight)\s*[,)]/g;
const skip = [/\.test\.tsx?$/, /(^|\/)test\//];

function* walk(p) {
  if (statSync(p).isDirectory()) for (const n of readdirSync(p)) yield* walk(path.join(p, n));
  else if (/\.(tsx?|css)$/.test(p)) yield p;
}

const problems = [];
for (const root of roots) {
  for (const file of walk(path.join(webRoot, root))) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (skip.some(r => r.test(rel))) continue;
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      for (const m of line.matchAll(LEGACY)) problems.push(`${rel}:${i + 1}  ${m[0]}`);
    });
  }
}
if (problems.length) {
  console.error(`Deprecated token names in rebuilt code (use the semantic roles of @northline/tokens):\n${problems.map(p => '  ' + p).join('\n')}`);
  process.exit(1);
}
console.log(`No deprecated token names in ${roots.join(', ')}.`);
