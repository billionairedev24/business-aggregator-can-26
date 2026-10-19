import { rmSync } from 'node:fs';

/** Before a `make ui-shots` run: forget the last run's lists of unanswered api requests and of overflowing elements (shoot.ts appends to them). */
export default function setup() {
  for (const app of ['consumer', 'studio', 'console']) {
    rmSync(new URL(`../../../../ui-shots/${app}/_missing-api.txt`, import.meta.url), { force: true });
    rmSync(new URL(`../../../../ui-shots/${app}/_overflow.txt`, import.meta.url), { force: true });
  }
}
