import { queryOptions, useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, ValidationError, http, xsrfToken, type FieldError } from '../../lib/http';

/** GET /api/v1/ai/status — AI actions are hidden when `available` is false (no model configured here). */
export const AiStatus = z.object({ available: z.boolean(), provider: z.string() });
export const aiStatusQuery = queryOptions({
  queryKey: ['ai', 'status'],
  queryFn: () => http('/api/v1/ai/status', {}, AiStatus),
  staleTime: 5 * 60_000,
});

export const ToolRun = z.object({ tool: z.string(), summary: z.string(), ok: z.boolean() });
export type ToolRun = z.infer<typeof ToolRun>;
export const PendingAction = z.object({ tool: z.string(), arguments: z.record(z.string(), z.unknown()), preview: z.string() });
export type PendingAction = z.infer<typeof PendingAction>;
export const Answer = z.object({
  content: z.string(),
  toolRuns: z.array(ToolRun),
  pending: PendingAction.nullish(),
  screen: z.string().nullish(),
  model: z.string(),
  usage: z.object({ modelCalls: z.number(), promptTokens: z.number(), completionTokens: z.number(), costUsd: z.number().nullish(), latencyMs: z.number() }).nullish(),
});
export type Answer = z.infer<typeof Answer>;

export interface Turn { role: 'user' | 'assistant'; content: string }

/** The assistant's failures the drawer explains: 429 (budget) and 503 (no model / provider down). */
export const aiErrorKind = (e: unknown): 'rate' | 'off' | 'other' =>
  e instanceof ApiError ? (e.status === 429 ? 'rate' : e.status === 503 ? 'off' : 'other') : 'other';

async function failure(res: Response): Promise<never> {
  const text = await res.text();
  let data: unknown = undefined;
  try { data = text ? JSON.parse(text) : undefined; } catch { /* not JSON */ }
  if (res.status === 422 && data && typeof data === 'object' && 'errors' in data) throw new ValidationError((data as { errors: FieldError[] }).errors);
  const detail = data && typeof data === 'object' && 'detail' in data ? String((data as { detail: unknown }).detail) : res.statusText;
  throw new ApiError(res.status, detail || `Request failed (${res.status})`, data);
}

/** Splits server-sent events into `{event, data}` frames as they arrive. */
export async function* sseFrames(body: ReadableStream<Uint8Array>): AsyncGenerator<{ event: string; data: string }> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (value) buffer += decoder.decode(value, { stream: true });
    let cut: number;
    while ((cut = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, cut);
      buffer = buffer.slice(cut + 2);
      let event = 'message';
      const data: string[] = [];
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
      }
      if (data.length) yield { event, data: data.join('\n') };
    }
    if (done) return;
  }
}

/**
 * POST …/assistant/chat/stream through the BFF (CSRF header, session cookie). Hands tool runs and answer text to the
 * callbacks as they arrive and resolves with the whole answer; a refusal before the first frame is an ordinary error.
 */
export async function streamChat(
  merchantId: string,
  body: { messages: Turn[]; screen?: string },
  on: { tool: (run: ToolRun) => void; delta: (text: string) => void },
  signal?: AbortSignal,
): Promise<Answer> {
  const headers: Record<string, string> = { accept: 'text/event-stream', 'content-type': 'application/json' };
  const token = xsrfToken();
  if (token) headers['x-xsrf-token'] = token;
  const res = await fetch(`/api/v1/merchants/${merchantId}/assistant/chat/stream`, { method: 'POST', headers, credentials: 'include', body: JSON.stringify(body), signal });
  if (!res.ok || !res.body) return failure(res);
  for await (const frame of sseFrames(res.body)) {
    const data: unknown = JSON.parse(frame.data);
    if (frame.event === 'tool') on.tool(ToolRun.parse(data));
    else if (frame.event === 'delta') on.delta(z.object({ text: z.string() }).parse(data).text);
    else if (frame.event === 'done') return Answer.parse(data);
    else if (frame.event === 'error') {
      const p = z.object({ status: z.number(), code: z.string(), detail: z.string().nullish() }).parse(data);
      throw new ApiError(p.status, p.detail ?? p.code, p);
    }
  }
  throw new ApiError(503, 'The answer was cut off.');
}

export const ActionResult = z.object({ tool: z.string(), summary: z.string(), screen: z.string().nullish() });
export type ActionResult = z.infer<typeof ActionResult>;

/** POST …/assistant/actions — runs a write the assistant proposed, after the person confirmed it. */
export const useConfirmAction = (merchantId: string) => useMutation({
  mutationFn: (a: PendingAction) => http(`/api/v1/merchants/${merchantId}/assistant/actions`, { method: 'POST', body: { tool: a.tool, arguments: a.arguments } }, ActionResult),
});

export const Insight = z.object({ title: z.string(), body: z.string(), bullets: z.array(z.string()), model: z.string() });
export type Insight = z.infer<typeof Insight>;
export type InsightScreen = 'dashboard' | 'earnings' | 'listings';

/** GET …/assistant/insights/{screen} — fetched when the person asks (each one costs a model call). */
export const insightQuery = (merchantId: string, screen: InsightScreen, lang: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'assistant', 'insight', screen, lang],
  queryFn: () => http(`/api/v1/merchants/${merchantId}/assistant/insights/${screen}`, {}, Insight),
  staleTime: 15 * 60_000,
  enabled: false,
  retry: false,
});
