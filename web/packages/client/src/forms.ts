import { ValidationError } from './http';

/**
 * Show a field error after the field is touched or after a submit attempt (validation-rules.md).
 * Works with TanStack Form field meta.
 */
export function visibleError(meta: { isTouched: boolean; errors: unknown[] }, submitted: boolean): string | undefined {
  if (!meta.isTouched && !submitted) return undefined;
  const e = meta.errors[0];
  if (!e) return undefined;
  return typeof e === 'string' ? e : typeof e === 'object' && e && 'message' in e ? String((e as { message: unknown }).message) : String(e);
}

/** Server 422 → field map; fields the form doesn't know land under `_form`. */
export function serverFieldErrors(err: unknown, known: readonly string[]): Record<string, string> {
  if (!(err instanceof ValidationError)) return {};
  const out: Record<string, string> = {};
  for (const e of err.errors) { const k = known.includes(e.field) ? e.field : '_form'; out[k] ??= e.message; }
  return out;
}

/** "N things need attention." summary count. */
export const attentionCount = (errors: Record<string, string | undefined>) => Object.values(errors).filter(Boolean).length;
