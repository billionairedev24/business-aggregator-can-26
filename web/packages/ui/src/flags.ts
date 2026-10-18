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
