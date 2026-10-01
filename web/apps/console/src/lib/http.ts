import { http as clientHttp, type RequestOptions } from '@northline/client';
import type { z } from 'zod';

// The shared HTTP client (@northline/client), plus the console's role view on every api call.
export * from '@northline/client';

/** S-90: the role the console acts with (api `X-Console-Role`; the api checks the person holds it). */
export const ROLE_VIEW_HEADER = 'X-Console-Role';
let roleView: string | undefined;

/** Narrows every later `/api` call to one held role (undefined = all of them). */
export function setRoleView(role: string | undefined) {
  roleView = role;
}
export const currentRoleView = () => roleView;

/** `http()` from @northline/client with the role view header on `/api` calls. */
export function http<T = unknown>(path: string, opts: RequestOptions = {}, schema?: z.ZodType<T>): Promise<T> {
  const headers = roleView && path.startsWith('/api/') ? { [ROLE_VIEW_HEADER]: roleView, ...opts.headers } : opts.headers;
  return clientHttp(path, { ...opts, headers }, schema);
}
