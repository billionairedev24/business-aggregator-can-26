import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http, ValidationError, xsrfToken, type FieldError } from '../../lib/http';

/** Studio › Standing (S-156; server: ca.northline.standing): consequences, appeals, dispute decisions to appeal, coaching. */
const base = (m: string) => `/api/v1/merchants/${m}/standing`;

export const KINDS = ['suspended', 'search_hidden', 'instant_book_off', 'photo_delay', 'no_show_warning'] as const;
export const FileView = z.object({ id: z.string(), name: z.string(), contentType: z.string(), size: z.number() });
export const Appeal = z.object({
  id: z.string(), number: z.string(), subjectType: z.enum(['dispute', 'consequence']), subjectId: z.string(), subjectKind: z.string(),
  subjectLabel: z.string().nullish(), statement: z.string(), state: z.enum(['open', 'in_review', 'granted', 'denied']), filedAt: z.string(),
  decidedAt: z.string().nullish(), decisionReason: z.string().nullish(), compensationCents: z.number(), files: z.array(FileView),
});
export type Appeal = z.infer<typeof Appeal>;
export const Consequence = z.object({
  id: z.string(), kind: z.string(), cause: z.string(), reason: z.string(), detail: z.record(z.string(), z.string()), startedAt: z.string(),
  endsAt: z.string().nullish(), active: z.boolean(), liftedAt: z.string().nullish(), liftReason: z.string().nullish(), liftedByStaff: z.boolean(),
  appealable: z.boolean(), appealBy: z.string().nullish(), appeal: Appeal.nullish(),
});
export type Consequence = z.infer<typeof Consequence>;
export const Coaching = z.object({
  id: z.string(), title: z.string(), note: z.string().nullish(), dueOn: z.string().nullish(), state: z.enum(['active', 'completed', 'cancelled']),
  createdAt: z.string(), closedAt: z.string().nullish(), closeReason: z.string().nullish(),
  tasks: z.array(z.object({ id: z.string(), title: z.string(), done: z.boolean(), doneAt: z.string().nullish() })),
});
export type Coaching = z.infer<typeof Coaching>;
export const DisputeToAppeal = z.object({
  disputeId: z.string(), caseNumber: z.string(), subject: z.string(), decision: z.string(), refundCents: z.number(), decidedAt: z.string(),
  appealBy: z.string(), note: z.string().nullish(),
});
export type DisputeToAppeal = z.infer<typeof DisputeToAppeal>;
export const Standing = z.object({
  active: z.array(Consequence), past: z.array(Consequence), appeals: z.array(Appeal), appealableDisputes: z.array(DisputeToAppeal),
  coaching: Coaching.nullish(), pastCoaching: z.array(Coaching),
});
export type Standing = z.infer<typeof Standing>;

export const standingKey = (m: string) => ['merchant', m, 'standing'] as const;
export const standingQuery = (m: string) => queryOptions({ queryKey: standingKey(m), queryFn: () => http(base(m), {}, Standing) });
export const appealFileUrl = (m: string, appealId: string, fileId: string) => `${base(m)}/appeals/${appealId}/files/${fileId}`;

/** Files go up as the raw file (Content-Type + X-File-Name), like dispute evidence. */
async function uploadFile(m: string, appealId: string, file: File): Promise<Appeal> {
  const xsrf = xsrfToken();
  const res = await fetch(`${base(m)}/appeals/${appealId}/files`, {
    method: 'POST', credentials: 'include', body: file,
    headers: { accept: 'application/json', 'content-type': file.type || 'application/octet-stream', 'x-file-name': encodeURIComponent(file.name), ...(xsrf ? { 'x-xsrf-token': xsrf } : {}) },
  });
  const text = await res.text();
  const data: unknown = text ? JSON.parse(text) : undefined;
  if (res.status === 422 && data && typeof data === 'object' && 'errors' in data) throw new ValidationError((data as { errors: FieldError[] }).errors);
  if (!res.ok) throw new ApiError(res.status, res.statusText || `Upload failed (${res.status})`, data);
  return Appeal.parse(data);
}

export interface AppealInput { subjectType: 'dispute' | 'consequence'; subjectId: string; statement: string; files: File[] }

/** Files the appeal, then attaches its files one by one (a refused file leaves the appeal filed). */
export function useFileAppeal(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (v: AppealInput) => {
      let appeal = await http(`${base(m)}/appeals`, { method: 'POST', body: { subjectType: v.subjectType, subjectId: v.subjectId, statement: v.statement } }, Appeal);
      for (const f of v.files) appeal = await uploadFile(m, appeal.id, f);
      return appeal;
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: standingKey(m) }),
  });
}

export function useTickTask(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { planId: string; taskId: string; done: boolean }) =>
      http(`${base(m)}/coaching/${v.planId}/tasks/${v.taskId}`, { method: 'PUT', body: { done: v.done } }, Coaching),
    onSuccess: () => void qc.invalidateQueries({ queryKey: standingKey(m) }),
  });
}

/** S-156: instant book's state (Northline's pause with its reason), shown with Availability › "How bookings come in". */
export const InstantBookState = z.object({
  enabled: z.boolean(), effective: z.boolean(), paused: z.boolean(), pausedAt: z.string().nullish(), pausedCause: z.string().nullish(), pausedReason: z.string().nullish(),
});
export const instantBookQuery = (m: string) => queryOptions({
  queryKey: ['merchant', m, 'instant-book'] as const,
  queryFn: () => http(`/api/v1/merchants/${m}/instant-book`, {}, InstantBookState),
});
