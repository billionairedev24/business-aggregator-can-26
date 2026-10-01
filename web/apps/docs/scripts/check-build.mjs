// S-126 build test: after `pnpm build`, every page the acceptance criteria name must exist — each runbook and each
// OpenAPI spec (Redoc and Scalar pages, the YAML), in English and French — and the public variant must contain no
// internal page. Run by `make docs` and the manual CI jobs.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const here = import.meta.dirname;
const build = resolve(here, '../build');
const repoDocs = resolve(process.env.NORTHLINE_DOCS_DIR ?? resolve(here, '../../../../docs'));
const variant = process.env.NORTHLINE_DOCS_VARIANT || 'internal';
const internal = variant === 'internal';

const specs = readdirSync(resolve(repoDocs, 'api/openapi'))
  .filter((f) => f.endsWith('.yaml'))
  .map((f) => f.slice(0, -'.yaml'.length));
const publicSpecs = ['api-public', 'api-partner', 'api-webhooks', 'auth-public'];
const runbooks = readdirSync(resolve(repoDocs, 'runbooks'))
  .filter((f) => f.endsWith('.md') && f !== 'README.md')
  .map((f) => f.slice(0, -'.md'.length));

const missing = [];
const present = [];
const must = (path) => (existsSync(resolve(build, path)) ? null : missing.push(path));
const mustNot = (path) => (existsSync(resolve(build, path)) ? present.push(path) : null);

for (const locale of ['', 'fr/']) {
  must(`${locale}index.html`);
  must(`${locale}api/index.html`);
  must(`${locale}guides/index.html`);
  must(`${locale}guides/authentication/index.html`);
  must(`${locale}guides/webhooks/index.html`);
  for (const spec of specs) {
    const expected = internal || publicSpecs.includes(spec);
    (expected ? must : mustNot)(`${locale}api/${spec}/index.html`);
    (expected ? must : mustNot)(`${locale}api/${spec}/scalar/index.html`);
  }
  for (const page of ['ARCHITECTURE', 'DATA_MODEL', 'DECISIONS', 'runbooks', 'security/s-20-auth-review', 'backlog', 'ai']) {
    (internal ? must : mustNot)(`${locale}docs/${page}/index.html`);
  }
  for (const runbook of runbooks) (internal ? must : mustNot)(`${locale}docs/runbooks/${runbook}/index.html`);
}
for (const spec of specs) (internal || publicSpecs.includes(spec) ? must : mustNot)(`openapi/${spec}.yaml`);
must('config.js');
const french = readFileSync(resolve(build, 'fr/guides/authentication/index.html'), 'utf8');
if (!french.includes('Authentification')) missing.push('fr/guides/authentication: French content');

if (missing.length || present.length) {
  if (missing.length) console.error(`Missing from the ${variant} build:\n  ${missing.join('\n  ')}`);
  if (present.length) console.error(`Must not be in the ${variant} build:\n  ${present.join('\n  ')}`);
  process.exit(1);
}
console.log(`docs build OK (${variant}): ${specs.length} specs, ${runbooks.length} runbooks, en + fr`);
