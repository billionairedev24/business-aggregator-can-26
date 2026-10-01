// Copies the committed OpenAPI specs of this variant (docs/api/openapi/<id>.yaml, src/content.ts) into static/openapi/,
// where Scalar and the download links fetch them. The specs stay where they are committed; the copy is a build artefact
// (git-ignored), and the public variant never receives an internal one.
import { copyFileSync, mkdirSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { specsFor, variantFrom } from '../src/content.ts';

const here = import.meta.dirname;
const source = resolve(process.env.NORTHLINE_DOCS_DIR ?? resolve(here, '../../../../docs'), 'api/openapi');
const target = resolve(here, '../static/openapi');
const variant = variantFrom(process.env.NORTHLINE_DOCS_VARIANT);
rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });
const specs = specsFor(variant);
for (const spec of specs) copyFileSync(resolve(source, `${spec.id}.yaml`), resolve(target, `${spec.id}.yaml`));
console.log(`openapi (${variant}): ${specs.length} specs → static/openapi`);

// Scalar's standalone bundle (pinned devDependency @scalar/api-reference), served by the site itself instead of the
// plugin's default — the unpinned "latest" from cdn.jsdelivr.net — so the pages load nothing from another origin.
// The package doesn't export ./package.json; its folder is found from this app's node_modules (pnpm links it there).
const scalar = resolve(here, '../node_modules/@scalar/api-reference/dist/browser/standalone.js');
mkdirSync(resolve(here, '../static/scalar'), { recursive: true });
copyFileSync(scalar, resolve(here, '../static/scalar/standalone.js'));
console.log('scalar: standalone.js → static/scalar');
