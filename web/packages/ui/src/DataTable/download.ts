/** Browser-only helpers for delivering a report. The builders live in `report.ts`. */

export function downloadBlob(data: BlobPart, type: string, fileName: string) {
  const url = URL.createObjectURL(new Blob([data], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(url);
    a.remove();
  }, 1000);
}

/** Opens the print-styled report in a new tab; it prints itself on load. Returns false if a popup blocker stopped it. */
export function openPrintWindow(html: string): boolean {
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
  const w = window.open(url, '_blank');
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
  return !!w;
}

const THEME_VARS = ['--color-text', '--color-bg', '--color-accent', '--font-body', '--font-heading'];

/** Resolved base tokens from the host element, so the print document matches the active theme. */
export function readThemeVars(el: Element | null): Record<string, string> {
  if (!el || typeof getComputedStyle === 'undefined') return {};
  const cs = getComputedStyle(el);
  return Object.fromEntries(THEME_VARS.map((k) => [k, cs.getPropertyValue(k).trim()]).filter(([, v]) => v));
}
