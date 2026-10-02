import { configureAxe } from 'vitest-axe';
import type { AxeResults, ImpactValue, Result } from 'axe-core';

/**
 * Component-level accessibility check (S-109): axe-core over a Testing Library container, WCAG 2.0–2.2 A and AA rules.
 *
 * jsdom has no layout, so `color-contrast` (and the target-size rule, which measures boxes) cannot run here: the token
 * contrast test (packages/ui `contrast.test.ts`) and the Playwright page sweep (`make a11y`) cover them. A component
 * rendered alone sits outside any landmark, so `region` is off too; the page sweep checks landmarks.
 */
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] as const;

const run = configureAxe({
  runOnly: { type: 'tag', values: [...WCAG_TAGS] },
  rules: { 'color-contrast': { enabled: false }, 'target-size': { enabled: false }, region: { enabled: false } },
});

export interface AxeOptions {
  /** Rule ids to switch off for this check, with the reason in the test (e.g. a third-party widget). */
  disable?: readonly string[];
}

const ORDER: Record<string, number> = { critical: 0, serious: 1, moderate: 2, minor: 3 };

/** One line per violation and node: "serious button-name: Buttons must have discernible text — <button …>". */
export function describeViolations(violations: readonly Result[]): string {
  return [...violations]
    .sort((a, b) => (ORDER[a.impact ?? 'minor'] ?? 4) - (ORDER[b.impact ?? 'minor'] ?? 4))
    .flatMap(v => v.nodes.map(n => `${v.impact ?? 'n/a'} ${v.id}: ${v.help} — ${n.html.slice(0, 160)}`))
    .join('\n');
}

/** The violations axe finds in `container` (default: the whole document). */
export async function axeViolations(container: Element | Document = document, opts: AxeOptions = {}): Promise<Result[]> {
  const rules = Object.fromEntries((opts.disable ?? []).map(id => [id, { enabled: false }]));
  const results: AxeResults = await run(container as Element, { rules });
  return results.violations;
}

/**
 * Fails the test with a readable list when axe finds a WCAG A/AA violation.
 *
 *   const { container } = render(<Dialog open … />);
 *   await expectNoAxeViolations(container);
 */
export async function expectNoAxeViolations(container: Element | Document = document, opts: AxeOptions = {}): Promise<void> {
  const violations = await axeViolations(container, opts);
  if (violations.length) throw new Error(`axe found ${violations.length} accessibility violation(s):\n${describeViolations(violations)}`);
}

export type { ImpactValue };
