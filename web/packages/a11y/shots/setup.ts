import { rmSync } from 'node:fs';

/** Before a `make ui-shots` run: forget the last run's list of unanswered api requests (shoot.ts appends to it). */
export default function setup() {
  for (const app of ['consumer', 'studio', 'console']) rmSync(new URL(`../../../../ui-shots/${app}/_missing-api.txt`, import.meta.url), { force: true });
}
