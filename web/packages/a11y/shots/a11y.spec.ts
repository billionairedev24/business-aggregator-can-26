import { appendFileSync, mkdirSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { check, type App } from '../pages/support';
import { ROUTES } from './routes';
import { LANGS, prepare, visit } from './shoot';

/**
 * S-180 (`make ui-a11y`): the accessibility check of `make a11y` (pages/support.ts `check`: axe for WCAG 2.0–2.2 A/AA,
 * <html lang>, text spacing, focus hidden by a sticky bar, motion under reduced motion, target sizes) on EVERY route
 * that `make ui-shots` photographs — the same built apps, demo data, pinned clocks and clicks (a sheet or a dialog
 * open) — at 1440 and 390 wide, in English and in French (UI_SHOTS_LANGS). One line per page and width goes to
 * ui-shots/<app>/_a11y.txt; the JSON of each check to a11y-results/pages/.
 *
 * Fails on: a critical or serious WCAG violation, a heading out of order or no h1, the wrong page language, a sideways
 * scroll with the 1.4.12 text spacing, a focused control hidden under a sticky bar (2.4.11), an animation still
 * running under reduced motion, or a pointer target under 24 × 24 px (2.5.8). Targets between 24 and 44 px are
 * listed (the project's own 44 px rule; the documented exceptions are in docs/a11y/audit.md § S-180), not failed.
 */
const OUT = new URL('../../../../ui-shots/', import.meta.url).pathname;
const WIDTHS = [[1440, 900], [390, 844]] as const;
const HEADINGS = new Set(['heading-order', 'page-has-heading-one']);
const size = (s: string): [number, number] => { const m = /(\d+)×(\d+)$/.exec(s); return m ? [Number(m[1]), Number(m[2])] : [0, 0]; };

for (const app of ['consumer', 'studio', 'console'] as const satisfies readonly App[]) {
  test.describe(app, () => {
    // each app's project (shots.config.ts, UI_SHOTS_A11Y=1) serves that app only
    test.beforeEach(({}, info) => test.skip(info.project.name !== `a11y-${app}`));
    for (const r of ROUTES[app]) {
      test(r.name, async ({ page }) => {
        mkdirSync(`${OUT}${app}`, { recursive: true });
        for (const lang of LANGS) {
          const p = lang === LANGS[0] ? page : await page.context().newPage();
          const missing = await prepare(p, app, r, lang);
          for (const [width, height] of WIDTHS) {
            await p.setViewportSize({ width, height });
            await visit(p, app, r, lang);
            const name = `${app}-${r.name}-${lang}-${width}`;
            const report = await check(p, name, r.path, lang, missing);
            const blockers = report.violations.filter(v => (v.wcag && (v.impact === 'critical' || v.impact === 'serious')) || HEADINGS.has(v.id))
              .map(v => `${v.impact} ${v.id} (${v.nodes}): ${v.targets.join(' | ')}`);
            const tiny = report.smallTargets.filter(t => { const [w, h] = size(t); return w < 24 || h < 24; });
            const small = report.smallTargets.filter(t => !tiny.includes(t));
            const minor = report.violations.filter(v => !blockers.some(b => b.includes(` ${v.id} `))).map(v => `${v.impact} ${v.id}`);
            const lines = [
              blockers.length ? `BLOCKERS ${blockers.join(' ; ')}` : '',
              tiny.length ? `UNDER 24px ${tiny.join(' ; ')}` : '',
              report.obscuredFocus.length ? `FOCUS HIDDEN ${report.obscuredFocus.join(' ; ')}` : '',
              report.runningAnimationsWithReducedMotion ? `MOTION ${report.runningAnimationsWithReducedMotion} running` : '',
              report.textSpacingOverflow ? 'TEXT SPACING sideways scroll' : '',
              small.length ? `24-43px (${report.targetsUnder44}) ${small.slice(0, 12).join(' ; ')}` : '',
              minor.length ? `minor ${[...new Set(minor)].join(', ')}` : '',
            ].filter(Boolean);
            appendFileSync(`${OUT}${app}/_a11y.txt`, `${r.name}-${lang}-${width}: ${lines.length ? lines.join(' | ') : 'ok'}\n`);
            expect.soft(blockers, `${name}: critical/serious WCAG violations, heading order`).toEqual([]);
            expect.soft(report.lang, `${name}: <html lang>`).toBe(lang === 'fr' ? 'fr-CA' : 'en-CA');
            expect.soft(tiny, `${name}: pointer targets under 24 px (WCAG 2.5.8)`).toEqual([]);
            expect.soft(report.obscuredFocus, `${name}: focus hidden by a sticky bar (WCAG 2.4.11)`).toEqual([]);
            expect.soft(report.runningAnimationsWithReducedMotion, `${name}: animations under reduced motion`).toBe(0);
            expect.soft(report.textSpacingOverflow, `${name}: sideways scroll with the 1.4.12 text spacing`).toBe(false);
          }
          if (p !== page) await p.close();
        }
      });
    }
  });
}
