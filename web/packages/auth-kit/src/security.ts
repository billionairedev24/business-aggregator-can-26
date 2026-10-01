import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '@northline/client';
import { authUrl } from './config';

/**
 * Account security at northline-auth (S-19, `/api/auth/security`), shared by the Studio's Settings › Security and the
 * consumer site's Security & sign-in tab (S-59): passkeys, the authenticator app, backup codes, recent sign-ins and
 * active sessions, plus the changes (add / remove a passkey, sign a session out, sign out everywhere else).
 */
export const ActiveSession = z.object({
  id: z.string(), device: z.string().nullish(), city: z.string().nullish(), ipApprox: z.string().nullish(), method: z.string().nullish(),
  signedInAt: z.string(), lastSeenAt: z.string().nullish(), apps: z.array(z.string()).default([]), current: z.boolean(),
});
export type ActiveSession = z.infer<typeof ActiveSession>;
export const Passkey = z.object({ id: z.string(), label: z.string(), createdAt: z.string().nullish(), lastUsedAt: z.string().nullish() });
export type Passkey = z.infer<typeof Passkey>;
export const Security = z.object({
  email: z.string().nullish(), mfaPrimary: z.string().nullish(),
  passkeys: z.array(Passkey),
  authenticator: z.boolean(), authenticatorSince: z.string().nullish(), backupCodesRemaining: z.number(), backupCodesIssuedAt: z.string().nullish(),
  signIns: z.array(z.object({ id: z.string(), device: z.string().nullish(), city: z.string().nullish(), method: z.string().nullish(), at: z.string(), lastSeenAt: z.string().nullish() })),
  sessions: z.array(ActiveSession).default([]),
});
export type Security = z.infer<typeof Security>;
export const securityKey = ['auth', 'security'] as const;

/**
 * `null` = the auth server has no second-factor session for this browser (the person confirms it's them first).
 * `current` = the BFF session's `sid` (GET /bff/session), so this browser's app session is marked current too (S-19).
 */
export const securityQuery = (current?: string | null) => queryOptions({
  queryKey: [...securityKey, current ?? null],
  queryFn: async (): Promise<Security | null> => {
    const q = current ? `?current=${encodeURIComponent(current)}` : '';
    try { return await http(authUrl(`/api/auth/security${q}`), {}, Security); } catch (e) { if (e instanceof ApiError && e.status === 401) return null; throw e; }
  },
  retry: false,
});

export const passkeyOptions = () => http(authUrl('/api/auth/security/passkeys/options'), { method: 'POST', body: {} }, z.record(z.string(), z.unknown()));
export const addPasskey = (credential: unknown, label: string) => http(authUrl('/api/auth/security/passkeys'), { method: 'POST', body: { credential, label } });

/**
 * S-19 changes. Each needs a second factor from the last 10 minutes: a 403 `step_up_required` means "confirm with the
 * passkey or authenticator code (`/api/auth/step-up/*`) and try again".
 */
export const removePasskey = (id: string) => http(authUrl(`/api/auth/security/passkeys/${encodeURIComponent(id)}`), { method: 'DELETE' }, z.object({ passkeys: z.array(Passkey) }));
export const revokeSession = (id: string, current?: string | null) =>
  http(authUrl(`/api/auth/security/sessions/${encodeURIComponent(id)}/revoke`), { method: 'POST', body: { current: current ?? null } }, z.object({ sessions: z.array(ActiveSession) }));
export const revokeOtherSessions = (current?: string | null) =>
  http(authUrl('/api/auth/security/sessions/revoke-others'), { method: 'POST', body: { current: current ?? null } }, z.object({ revoked: z.number() }));

/** Why a security change was refused, from the auth server's ProblemDetail `code`. */
export type SecurityChangeError = 'step_up_required' | 'last_factor' | 'current_session' | 'not_found' | 'rate_limited' | 'signed_out' | 'failed';
export function securityChangeError(e: unknown): SecurityChangeError {
  if (!(e instanceof ApiError)) return 'failed';
  if (e.status === 401) return 'signed_out';
  if (e.status === 429) return 'rate_limited';
  const code = e.body && typeof e.body === 'object' && 'code' in e.body ? String((e.body as { code: unknown }).code) : '';
  return (['step_up_required', 'last_factor', 'current_session', 'not_found'] as const).find(c => c === code) ?? 'failed';
}
