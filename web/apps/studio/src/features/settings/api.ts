import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { authUrl } from '../../lib/auth-server';
import { ApiError, http } from '../../lib/http';

/**
 * Settings (design 02 › SETTINGS). Business + team live in the merchants module, API keys / webhooks / audit log in the
 * developer module, the notification matrix in messaging — all under `/api/v1/merchants/{id}/settings/…`. Security
 * talks to northline-auth (`/api/auth/security`, `/api/auth/backup-codes`), which owns passkeys and second factors.
 */
export { SETTINGS_TABS, type SettingsTab } from './tabs';

const base = (m: string) => `/api/v1/merchants/${m}/settings`;
export const settingsKey = (m: string, ...rest: string[]) => ['merchant', m, 'settings', ...rest];

// ── Business ────────────────────────────────────────────────────────────────────────────────────────────────────────
export const CancellationPolicy = z.enum(['flexible', '12h', '24h']);
export const Business = z.object({
  type: z.enum(['provider', 'seller', 'kitchen', 'both']), structure: z.string().nullish(), displayName: z.string(), legalName: z.string(),
  gstNumber: z.string().nullish(), gstRequired: z.boolean(), serviceArea: z.string().nullish(), cancellationPolicy: CancellationPolicy,
  autoAcceptQuoteCents: z.number().nullish(), languages: z.array(z.string()), storeSlug: z.string().nullish(),
});
export type Business = z.infer<typeof Business>;
export interface BusinessInput {
  displayName: string; legalName: string; gstNumber: string | null; serviceArea: string | null;
  cancellationPolicy: z.infer<typeof CancellationPolicy>; autoAcceptQuoteCents: number | null; languages: string[];
}
export const businessQuery = (m: string) => queryOptions({ queryKey: settingsKey(m, 'business'), queryFn: () => http(`${base(m)}/business`, {}, Business) });
export function useSaveBusiness(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: BusinessInput) => http(`${base(m)}/business`, { method: 'PUT', body }, Business),
    onSuccess: data => {
      qc.setQueryData(businessQuery(m).queryKey, data);
      void qc.invalidateQueries({ queryKey: ['merchant', m], exact: true });
      void qc.invalidateQueries({ queryKey: ['me', 'businesses'] });
    },
  });
}

// ── Team & roles ────────────────────────────────────────────────────────────────────────────────────────────────────
export const Role = z.enum(['owner', 'technician', 'bookkeeper', 'cook']);
export type Role = z.infer<typeof Role>;
export const Member = z.object({ userId: z.string(), name: z.string(), role: Role, secondFactor: z.enum(['passkey', 'totp', 'sms']).nullish(), you: z.boolean(), joinedAt: z.string() });
export type Member = z.infer<typeof Member>;
export const Invitation = z.object({ id: z.string(), role: Role, email: z.string().nullish(), phone: z.string().nullish(), state: z.enum(['pending', 'expired']), createdAt: z.string(), expiresAt: z.string() });
export type Invitation = z.infer<typeof Invitation>;
export const Team = z.object({ members: z.array(Member), invitations: z.array(Invitation), roles: z.array(Role) });
export type Team = z.infer<typeof Team>;
export const InvitationCreated = z.object({ invitation: Invitation, inviteUrl: z.string(), sent: z.boolean() });
export type InvitationCreated = z.infer<typeof InvitationCreated>;

export const teamQuery = (m: string) => queryOptions({ queryKey: settingsKey(m, 'team'), queryFn: () => http(`${base(m)}/team`, {}, Team) });
function useTeamRefresh(m: string) {
  const qc = useQueryClient();
  return () => Promise.all([qc.invalidateQueries({ queryKey: settingsKey(m, 'team') }), qc.invalidateQueries({ queryKey: ['merchant', m], exact: true })]);
}
export function useInvite(m: string) {
  const refresh = useTeamRefresh(m);
  return useMutation({
    mutationFn: (body: { email?: string; phone?: string; role: string }) => http(`${base(m)}/team/invitations`, { method: 'POST', body }, InvitationCreated),
    onSuccess: () => void refresh(),
  });
}
export function useWithdrawInvitation(m: string) {
  const qc = useQueryClient();
  const k = teamQuery(m).queryKey;
  return useMutation({
    mutationFn: (id: string) => http(`${base(m)}/team/invitations/${id}`, { method: 'DELETE' }),
    onMutate: async id => { await qc.cancelQueries({ queryKey: k }); const prev = qc.getQueryData(k); qc.setQueryData(k, v => v && { ...v, invitations: v.invitations.filter(i => i.id !== id) }); return { prev }; },
    onError: (_e, _id, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSettled: () => qc.invalidateQueries({ queryKey: k }),
  });
}
export function useChangeRole(m: string) {
  const refresh = useTeamRefresh(m);
  return useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: Role }) => http(`${base(m)}/team/members/${userId}`, { method: 'PATCH', body: { role } }, Member),
    onSuccess: () => void refresh(),
  });
}
export function useRemoveMember(m: string) {
  const refresh = useTeamRefresh(m);
  return useMutation({ mutationFn: (userId: string) => http(`${base(m)}/team/members/${userId}`, { method: 'DELETE' }), onSuccess: () => void refresh() });
}

/** The invitee's side: `/api/v1/team-invitations/{token}` (any signed-in user). */
export const InvitationPreview = z.object({ merchantId: z.string(), businessName: z.string(), businessType: z.string(), role: Role, state: z.enum(['pending', 'expired', 'accepted', 'revoked']), forYou: z.boolean() });
export type InvitationPreview = z.infer<typeof InvitationPreview>;
export const invitationQuery = (token: string) => queryOptions({ queryKey: ['team-invitation', token], queryFn: () => http(`/api/v1/team-invitations/${encodeURIComponent(token)}`, {}, InvitationPreview), retry: false });
export function useAcceptInvitation(token: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => http(`/api/v1/team-invitations/${encodeURIComponent(token)}/accept`, { method: 'POST' }, z.object({ merchantId: z.string(), role: Role })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['me', 'businesses'] }),
  });
}

// ── Security (northline-auth) ───────────────────────────────────────────────────────────────────────────────────────
// The schemas, query and changes are shared with the consumer site's Security tab (S-59): @northline/auth-kit.
export {
  ActiveSession, Passkey, Security, securityKey, securityQuery, passkeyOptions, addPasskey, removePasskey, revokeSession,
  revokeOtherSessions, securityChangeError, type SecurityChangeError,
} from '@northline/auth-kit';
export function useNewBackupCodes() {
  return useMutation({
    mutationFn: () => http(authUrl('/api/auth/backup-codes'), { method: 'POST', body: {} }, z.object({ codes: z.array(z.string()) })),
  });
}

// ── Notifications ───────────────────────────────────────────────────────────────────────────────────────────────────
export const NOTIFICATION_EVENTS = ['new_booking', 'quote_request', 'customer_message', 'payout', 'dispute', 'low_stock', 'quality'] as const;
export type NotificationEvent = (typeof NOTIFICATION_EVENTS)[number];
export const CHANNELS = ['push', 'sms', 'email'] as const;
export type Channel = (typeof CHANNELS)[number];
export const Matrix = z.object({ matrix: z.record(z.string(), z.record(z.string(), z.boolean())), quietFrom: z.string(), quietTo: z.string() });
export type Matrix = z.infer<typeof Matrix>;
export const notificationsQuery = (m: string) => queryOptions({ queryKey: settingsKey(m, 'notifications'), queryFn: () => http(`${base(m)}/notifications`, {}, Matrix) });
export function useToggleNotification(m: string) {
  const qc = useQueryClient();
  const k = notificationsQuery(m).queryKey;
  return useMutation({
    mutationFn: ({ event, channel, on }: { event: NotificationEvent; channel: Channel; on: boolean }) =>
      http(`${base(m)}/notifications`, { method: 'PUT', body: { matrix: { [event]: { [channel]: on } } } }, Matrix),
    onMutate: async ({ event, channel, on }) => {
      await qc.cancelQueries({ queryKey: k });
      const prev = qc.getQueryData(k);
      qc.setQueryData(k, v => v && { ...v, matrix: { ...v.matrix, [event]: { ...v.matrix[event], [channel]: on } } });
      return { prev };
    },
    onError: (_e, _v, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSuccess: data => qc.setQueryData(k, data),
  });
}

// ── API & integrations ──────────────────────────────────────────────────────────────────────────────────────────────
export const ApiKey = z.object({ id: z.string(), name: z.string(), scopes: z.array(z.string()), prefix: z.string(), rateLimit: z.number(), createdAt: z.string(), lastUsedAt: z.string().nullish() });
export type ApiKey = z.infer<typeof ApiKey>;
export const Webhook = z.object({
  id: z.string(), url: z.string(), events: z.array(z.string()), active: z.boolean(), signature: z.string(), createdAt: z.string(), lastStatus: z.number().nullish(), lastDeliveryAt: z.string().nullish(),
  failingSince: z.string().nullish(), disabledAt: z.string().nullish(), previousSecretUntil: z.string().nullish(),
});
export type Webhook = z.infer<typeof Webhook>;
/** S-33 delivery log: one row per event sent to an endpoint (plus resends and test events), newest attempts first. */
export const WebhookAttempt = z.object({ attempt: z.number(), at: z.string(), statusCode: z.number().nullish(), durationMs: z.number().nullish(), error: z.string().nullish(), responseSnippet: z.string().nullish() });
export const WebhookDelivery = z.object({
  id: z.string(), eventId: z.string(), eventType: z.string().nullish(), state: z.enum(['pending', 'succeeded', 'failed']), attempts: z.number(),
  statusCode: z.number().nullish(), lastAttemptAt: z.string().nullish(), nextAttemptAt: z.string().nullish(), durationMs: z.number().nullish(),
  error: z.string().nullish(), responseSnippet: z.string().nullish(), test: z.boolean(), resendOf: z.string().nullish(), createdAt: z.string(),
  history: z.array(WebhookAttempt),
});
export type WebhookDelivery = z.infer<typeof WebhookDelivery>;
export const DeveloperOptions = z.object({ scopes: z.array(z.string()), events: z.array(z.string()) });
export const AuditEntry = z.object({ id: z.string(), at: z.string(), actorId: z.string().nullish(), actorName: z.string().nullish(), role: z.string().nullish(), action: z.string(), targetType: z.string().nullish(), targetId: z.string().nullish() });
export type AuditEntry = z.infer<typeof AuditEntry>;
const items = <T extends z.ZodType>(s: T) => z.object({ items: z.array(s) }).transform(r => r.items);

export const apiKeysQuery = (m: string) => queryOptions({ queryKey: settingsKey(m, 'api-keys'), queryFn: () => http(`${base(m)}/api-keys`, {}, items(ApiKey)) });
export const webhooksQuery = (m: string) => queryOptions({ queryKey: settingsKey(m, 'webhooks'), queryFn: () => http(`${base(m)}/webhooks`, {}, items(Webhook)) });
export const deliveriesQuery = (m: string, endpointId: string) => queryOptions({ queryKey: settingsKey(m, 'webhooks', endpointId, 'deliveries'), queryFn: () => http(`${base(m)}/webhooks/${endpointId}/deliveries`, {}, items(WebhookDelivery)), refetchInterval: 15_000 });
export const developerOptionsQuery = (m: string) => queryOptions({ queryKey: settingsKey(m, 'developer-options'), queryFn: () => http(`${base(m)}/developer-options`, {}, DeveloperOptions), staleTime: Infinity });
export const auditLogQuery = (m: string) => queryOptions({ queryKey: settingsKey(m, 'audit-log'), queryFn: () => http(`${base(m)}/audit-log`, {}, items(AuditEntry)) });

export function useIssueKey(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; scopes: string[] }) => http(`${base(m)}/api-keys`, { method: 'POST', body }, z.object({ key: ApiKey, secret: z.string() })),
    onSuccess: ({ key }) => qc.setQueryData<ApiKey[]>(settingsKey(m, 'api-keys'), list => [...(list ?? []), key]),
  });
}
export function useRevokeKey(m: string) {
  const qc = useQueryClient();
  const k = settingsKey(m, 'api-keys');
  return useMutation({
    mutationFn: (id: string) => http(`${base(m)}/api-keys/${id}`, { method: 'DELETE' }),
    onMutate: async id => { await qc.cancelQueries({ queryKey: k }); const prev = qc.getQueryData<ApiKey[]>(k); qc.setQueryData<ApiKey[]>(k, list => list?.filter(x => x.id !== id)); return { prev }; },
    onError: (_e, _id, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSettled: () => qc.invalidateQueries({ queryKey: k }),
  });
}
const WithSecret = z.object({ endpoint: Webhook, secret: z.string() });
export function useAddWebhook(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { url: string; events: string[] }) => http(`${base(m)}/webhooks`, { method: 'POST', body }, WithSecret),
    onSuccess: ({ endpoint }) => qc.setQueryData<Webhook[]>(settingsKey(m, 'webhooks'), list => [...(list ?? []), endpoint]),
  });
}
/** Rotate; the old secret keeps signing for `overlapHours` (0 = stops at once). */
export function useRotateWebhook(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, overlapHours }: { id: string; overlapHours: number }) => http(`${base(m)}/webhooks/${id}/secret`, { method: 'POST', body: { overlapHours } }, WithSecret),
    onSuccess: ({ endpoint }) => qc.setQueryData<Webhook[]>(settingsKey(m, 'webhooks'), list => list?.map(w => (w.id === endpoint.id ? endpoint : w))),
  });
}
export function useEnableWebhook(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => http(`${base(m)}/webhooks/${id}/enable`, { method: 'POST' }, Webhook),
    onSuccess: endpoint => qc.setQueryData<Webhook[]>(settingsKey(m, 'webhooks'), list => list?.map(w => (w.id === endpoint.id ? endpoint : w))),
  });
}
/** Resend a finished delivery, or queue a `webhook.test` event; the worker sends it within seconds. */
export function useQueueDelivery(m: string, endpointId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (what: { resend: string } | { test: true }) =>
      http('resend' in what ? `${base(m)}/webhooks/${endpointId}/deliveries/${what.resend}/resend` : `${base(m)}/webhooks/${endpointId}/test`, { method: 'POST' }, WebhookDelivery),
    onSuccess: queued => qc.setQueryData<WebhookDelivery[]>(settingsKey(m, 'webhooks', endpointId, 'deliveries'), list => [queued, ...(list ?? [])]),
    onSettled: () => qc.invalidateQueries({ queryKey: settingsKey(m, 'webhooks', endpointId, 'deliveries') }),
  });
}
export function useRemoveWebhook(m: string) {
  const qc = useQueryClient();
  const k = settingsKey(m, 'webhooks');
  return useMutation({
    mutationFn: (id: string) => http(`${base(m)}/webhooks/${id}`, { method: 'DELETE' }),
    onMutate: async id => { await qc.cancelQueries({ queryKey: k }); const prev = qc.getQueryData<Webhook[]>(k); qc.setQueryData<Webhook[]>(k, list => list?.filter(x => x.id !== id)); return { prev }; },
    onError: (_e, _id, ctx) => { if (ctx?.prev) qc.setQueryData(k, ctx.prev); },
    onSettled: () => qc.invalidateQueries({ queryKey: k }),
  });
}
