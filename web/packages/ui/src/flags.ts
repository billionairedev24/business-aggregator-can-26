/// <reference types="vite/client" />
/**
 * The nav flag of the redesign (docs/design/redesign-spec.md § 3): pilot and testing tools — the "Send feedback"
 * control, the console's Pilot, UAT, Go-live and On-call screens, its API screen — are hidden from the product UI
 * unless the build sets `VITE_NL_TESTING_TOOLS=1` (a pilot or UAT environment). Hidden means out of the navigation and
 * the shells; the routes and every backend endpoint stay.
 */
export function testingToolsEnabled(): boolean {
  // written out in full: Vite replaces `import.meta.env.VITE_…` at build time only in this exact form
  const v: unknown = import.meta.env.VITE_NL_TESTING_TOOLS;
  return v === true || v === '1' || v === 'true';
}

/**
 * The second flag of the redesign's thrash list (§ 3.6): what the demo hides beyond the testing tools — the account's
 * Wallet & points, Dietary, Plus and Language pages. Hidden unless the build sets `VITE_NL_FULL_NAV=1`; the routes and
 * every backend endpoint stay. (Food left this flag with E-15, S-170/S-171: the locked mockups show it in the
 * department strip, the search scope and the home, DECISIONS 2026-10-23.)
 */
export function fullNavEnabled(): boolean {
  const v: unknown = import.meta.env.VITE_NL_FULL_NAV;
  return v === true || v === '1' || v === 'true';
}
