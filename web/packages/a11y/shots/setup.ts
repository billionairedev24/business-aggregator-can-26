import { rmSync } from 'node:fs';

/** Before a `make ui-shots` run: forget the last run's lists (unanswered api requests, overflowing, cut-off and untranslated text, the a11y findings: shoot.ts and a11y.spec.ts append to them). */
export default function setup() {
  const apps = process.env.UI_SHOTS_APPS?.split(',') ?? ['consumer', 'studio', 'console'];
  for (const app of apps) {
    if (process.env.UI_SHOTS_A11Y !== '1') rmSync(new URL(`../../../../ui-shots/${app}/_missing-api.txt`, import.meta.url), { force: true });
    // an accessibility run (UI_SHOTS_A11Y=1) starts its own lists only: one per width, so runs per app and width add up
    const lists = process.env.UI_SHOTS_A11Y === '1'
      ? [...(process.env.UI_A11Y_KEYBOARD === '1' ? [] : (process.env.UI_A11Y_WIDTHS ?? '1440,390').split(',').map(w => `_a11y-${w}`)), ...(process.env.UI_A11Y_WIDTHS ? [] : ['_keyboard'])]
      : ['_overflow', '_truncated', '_untranslated'];
    for (const list of lists) rmSync(new URL(`../../../../ui-shots/${app}/${list}.txt`, import.meta.url), { force: true });
  }
}
