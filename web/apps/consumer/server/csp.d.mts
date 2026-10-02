export interface ScriptSource { origin: string; script: string; why: string; integrity: string }
export const SCRIPT_INVENTORY: readonly ScriptSource[];
export const CSP_REPORT_PATH: string;
export const CSP_REPORT_MAX_BODY: number;
export function contentSecurityPolicy(options?: { authOrigin?: string }): string;
export function cspHeaders(options?: { authOrigin?: string }): Record<string, string>;
export function isCspReportPath(pathname: string): boolean;
export function cspReportLines(body: string): string[];
export function createCspReporter(options?: { log?: (line: string) => void; perMinute?: number; now?: () => number }):
  (body: string) => number;
