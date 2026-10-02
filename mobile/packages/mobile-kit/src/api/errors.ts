/** A field message of a 422 answer (`errors[]` of the api's problem details). */
export interface FieldError {
  field?: string;
  /** The rule that failed (`required`, `format`, `unique`, `mismatch`, …: docs/spec/validation-rules.md). */
  rule?: string;
  code?: string;
  message: string;
}

/**
 * An answer other than 2xx. `code` is the api's machine code (`not_packed`, `too_many_pings`, …), `detail` its
 * sentence in the request's language, `errors` the field messages of a 422.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    readonly detail: string | undefined,
    readonly errors: FieldError[] = [],
    /** Seconds, from `Retry-After`. */
    readonly retryAfter?: number,
    /** The whole problem-details body (e.g. northline-auth's `retryAfterSeconds` of a 429 `otp_throttled`). */
    readonly body: Record<string, unknown> = {},
  ) {
    // The message for the person: the first field message, else the detail.
    super(errors[0]?.message ?? detail ?? code ?? `HTTP ${status}`);
    this.name = 'ApiError';
  }

  /** Worth sending again later, unchanged: the server or the network had a problem, not the request. */
  get transient(): boolean {
    return this.status === 408 || this.status === 429 || this.status >= 500;
  }

  /** The message for one field of a 422, if the api sent one. */
  fieldMessage(field: string): string | undefined {
    return this.errors.find((e) => e.field === field)?.message;
  }
}

/** The request never got an answer (offline, DNS, time-out). */
export class NetworkError extends Error {
  readonly transient = true;

  constructor(cause?: unknown) {
    super(cause instanceof Error ? cause.message : 'Network request failed');
    this.name = 'NetworkError';
  }
}

/** The sign-in ended (refresh refused, reuse detected, signed out elsewhere): sign in again. */
export class SignedOutError extends Error {
  constructor(reason = 'signed_out') {
    super(reason);
    this.name = 'SignedOutError';
  }
}

export function retryAfterSeconds(header: string | null): number | undefined {
  if (!header) return undefined;
  const n = Number(header);
  if (Number.isFinite(n)) return Math.max(0, n);
  const at = Date.parse(header);
  return Number.isNaN(at) ? undefined : Math.max(0, Math.ceil((at - Date.now()) / 1000));
}

export async function apiErrorOf(res: Response): Promise<ApiError> {
  let body: { code?: string; detail?: string; title?: string; errors?: FieldError[]; error?: string; [k: string]: unknown } = {};
  try {
    body = (await res.json()) as typeof body;
  } catch {
    // not JSON
  }
  return new ApiError(
    res.status,
    body.code ?? body.error,
    body.detail ?? body.title,
    Array.isArray(body.errors) ? body.errors : [],
    retryAfterSeconds(res.headers.get('retry-after')),
    body,
  );
}
