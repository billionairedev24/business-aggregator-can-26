/** What every fixture area gets: the parsed request and helpers to answer like the real servers. */
export interface FixtureRequest {
  method: string;
  url: URL;
  /** The path after `/api/v1` for api calls, else the URL's path. */
  path: string;
  headers: Record<string, string>;
  /** JSON body (or form fields of a form-encoded body). */
  body: Record<string, unknown>;
}

export interface FixtureContext {
  now(): number;
  answer(status: number, body?: unknown, headers?: Record<string, string>): Response;
  /** A redirect (302) with a Location, as northline-auth answers /oauth2/authorize. */
  redirect(location: string): Response;
}

/** An area of the fixture backend: answers its requests, `undefined` for anything else. */
export type FixtureArea = (req: FixtureRequest) => Response | Promise<Response> | undefined;

export const lower = (h: HeadersInit | undefined): Record<string, string> => {
  const out: Record<string, string> = {};
  if (!h) return out;
  if (typeof (h as Headers).forEach === 'function' && !(Array.isArray(h))) (h as Headers).forEach((v, k) => (out[k.toLowerCase()] = v));
  else Object.entries(h as Record<string, string>).forEach(([k, v]) => (out[k.toLowerCase()] = v));
  return out;
};
