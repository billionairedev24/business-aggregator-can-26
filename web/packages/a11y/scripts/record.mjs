// Records the api answers of the apps' vitest suites into fixtures/<app>.json for the page sweep (S-109).
//   node scripts/record.mjs [studio|consumer|console …]     (default: all three)
// Each suite runs once with NL_A11Y_RECORD (src/record.ts wraps every fetch stub); the lines are reduced to the first
// successful answer per request. Rerun after a screen's api changes; the sweep's overrides (pages/support.ts) win.
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { reduce } from './reduce.mjs';

const here = new URL('..', import.meta.url).pathname;
const apps = process.argv.slice(2).length ? process.argv.slice(2) : ['studio', 'consumer', 'console'];
mkdirSync(join(here, 'fixtures'), { recursive: true });
for (const app of apps) {
  const file = join(tmpdir(), `nl-a11y-${app}-${process.pid}.jsonl`);
  rmSync(file, { force: true });
  writeFileSync(file, '');
  console.log(`recording ${app}…`);
  try {
    // one worker: the lines are appended by the test processes; a failing test still records what it got
    execFileSync('pnpm', ['--filter', `@northline/${app}`, 'exec', 'vitest', 'run', '--maxWorkers=1', '--minWorkers=1'], {
      cwd: join(here, '..', '..'), stdio: ['ignore', 'ignore', 'inherit'], env: { ...process.env, NL_A11Y_RECORD: file },
    });
  } catch { console.warn(`  ${app}: some tests failed while recording; keeping what was recorded`); }
  const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean).map(l => JSON.parse(l));
  const fixtures = reduce(lines);
  writeFileSync(join(here, 'fixtures', `${app}.json`), JSON.stringify(fixtures, null, 1) + '\n');
  console.log(`  ${lines.length} answers → ${Object.keys(fixtures.exact).length} requests, ${Object.keys(fixtures.pattern).length} patterns`);
  rmSync(file, { force: true });
}
