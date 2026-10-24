import { rmSync } from 'node:fs';

/** Before a `make ui-shots` run: forget the last run's lists (unanswered api requests, overflowing, cut-off and untranslated text, the a11y findings: shoot.ts and a11y.spec.ts append to them). */
export default function setup() {
  for (const app of ['consumer', 'studio', 'console']) {
    rmSync(new URL(`../../../../ui-shots/${app}/_missing-api.txt`, import.meta.url), { force: true });
    for (const list of ['_overflow', '_truncated', '_untranslated', '_a11y']) rmSync(new URL(`../../../../ui-shots/${app}/${list}.txt`, import.meta.url), { force: true });
  }
}
