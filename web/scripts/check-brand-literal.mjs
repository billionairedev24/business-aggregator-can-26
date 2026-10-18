#!/usr/bin/env node
// Lint (redesign 2026-10-18): the product's name is never written into UI strings. Copy says `{brand}` (filled by
// `useT` / `messagesFor` from web/packages/tokens/brand.json), code uses `brand.name` from @northline/tokens, and the
// mark + wordmark come from <Logo/> — so renaming the product is one edit to brand.json. Scans the string literals,
// template strings and JSX text of apps/*/src, packages/ui/src and packages/auth-kit/src (TypeScript's parser: names
// of identifiers, keys and comments are not copy). Tests are skipped. A genuine exception carries `brand-literal-ok`
// in a comment on the same or the previous line, with a reason.
//
//   node scripts/check-brand-literal.mjs        (from web/, or `pnpm lint:brand`)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const brand = JSON.parse(readFileSync(path.join(webRoot, 'packages/tokens/brand.json'), 'utf8')).identity;
// the current name and short name, as whole words (a key like `fundedNorthline` or the domain `northline.ca` is fine)
const names = [...new Set([brand.name, brand.shortName].filter(Boolean))];
const LITERAL = new RegExp(`(?<![\\w.@/-])(?:${names.map(n => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})(?![\\w-])`);
const roots = ['packages/ui/src', 'packages/auth-kit/src', ...readdirSync(path.join(webRoot, 'apps')).map(a => `apps/${a}/src`)];
const skip = [/\.test\.tsx?$/, /\.spec\.tsx?$/, /(^|\/)test\//, /(^|\/)__tests__\//, /(^|\/)node_modules\//, /\.gen\.ts$/];

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) yield* walk(full);
    else if (/\.(tsx?|mjs)$/.test(name)) yield full;
  }
}

const problems = [];
for (const root of roots) {
  const abs = path.join(webRoot, root);
  try { statSync(abs); } catch { continue; }
  for (const file of walk(abs)) {
    const rel = path.relative(webRoot, file).split(path.sep).join('/');
    if (skip.some(r => r.test(rel))) continue;
    const src = readFileSync(file, 'utf8');
    if (!names.some(n => src.includes(n))) continue;
    const lines = src.split('\n');
    const sf = ts.createSourceFile(file, src, ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    const visit = node => {
      const copy = ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateHead(node) || ts.isTemplateMiddle(node)
        || ts.isTemplateTail(node) || ts.isJsxText(node);
      if (copy) {
        const text = node.getText(sf);
        if (LITERAL.test(text)) {
          const { line, character } = sf.getLineAndCharacterOfPosition(node.getStart(sf));
          const ok = [lines[line], lines[line - 1]].some(l => l?.includes('brand-literal-ok'));
          if (!ok) problems.push(`${rel}:${line + 1}:${character + 1}  ${text.trim().slice(0, 80)}`);
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  }
}

if (problems.length) {
  console.error(`The product's name is written into UI strings (use {brand} in messages, brand.name in code, <Logo/> for the mark):\n${problems.map(p => '  ' + p).join('\n')}`);
  console.error(`\n${problems.length} problem(s). A genuine exception gets a \`brand-literal-ok: <reason>\` comment.`);
  process.exit(1);
}
console.log(`No literal "${names.join('" / "')}" in the UI strings of ${roots.join(', ')}.`);
