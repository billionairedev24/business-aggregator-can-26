import { useCallback } from 'react';
import { z } from 'zod';
import { useLocale } from '@northline/ui';
import type { Attachment } from './api';

/**
 * Messages and Help validation — identical to the server's (ca.northline.messaging.domain.MessagingRules), so a 422
 * and a client-side check read the same. validation-rules.md has no section for them; see DECISIONS.md.
 */
export const MSG = {
  MESSAGE_REQUIRED: 'Write a message or attach a file.',
  MESSAGE_TOO_LONG: 'Keep messages under 2,000 characters.',
  TOO_MANY_FILES: 'Attach up to 5 files.',
  FILE_GONE: 'That file is no longer available. Attach it again.',
  FILE_REQUIRED: 'Choose a file to attach.',
  FILE_TYPE: 'Attach JPG, PNG, HEIC or PDF files.',
  FILE_TOO_LARGE: 'Files can be up to 10 MB.',
  TOPIC_REQUIRED: 'Choose a topic.',
  CASE_BODY_REQUIRED: "Tell us what's happening.",
  CASE_BODY_TOO_LONG: 'Keep it under 4,000 characters.',
  CHANNEL_REQUIRED: 'Choose how we should reach you.',
  RELATED_INVALID: 'Pick a record from the list.',
} as const;

export const LIMITS = { message: 2000, files: 5, fileBytes: 10 * 1024 * 1024, caseBody: 4000 } as const;
const FILE_TYPES = ['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'application/pdf'];
export const FILE_ACCEPT = '.jpg,.jpeg,.png,.heic,.heif,.pdf,image/jpeg,image/png,image/heic,image/heif,application/pdf';

const FR: Record<string, string> = {
  [MSG.MESSAGE_REQUIRED]: 'Écrivez un message ou joignez un fichier.',
  [MSG.MESSAGE_TOO_LONG]: 'Les messages doivent faire moins de 2 000 caractères.',
  [MSG.TOO_MANY_FILES]: "Joignez jusqu'à 5 fichiers.",
  [MSG.FILE_GONE]: "Ce fichier n'est plus disponible. Joignez-le de nouveau.",
  [MSG.FILE_REQUIRED]: 'Choisissez un fichier à joindre.',
  [MSG.FILE_TYPE]: 'Joignez des fichiers JPG, PNG, HEIC ou PDF.',
  [MSG.FILE_TOO_LARGE]: "Les fichiers peuvent faire jusqu'à 10 Mo.",
  [MSG.TOPIC_REQUIRED]: 'Choisissez un sujet.',
  [MSG.CASE_BODY_REQUIRED]: 'Dites-nous ce qui se passe.',
  [MSG.CASE_BODY_TOO_LONG]: 'Moins de 4 000 caractères, svp.',
  [MSG.CHANNEL_REQUIRED]: 'Choisissez comment vous joindre.',
  [MSG.RELATED_INVALID]: 'Choisissez un dossier dans la liste.',
};

/** Localises a validation message (client-side or from a 422). Unknown messages pass through. */
export function useMessageT() {
  const { locale } = useLocale();
  return useCallback((message: string | undefined) => (message && locale === 'fr' ? FR[message] ?? message : message), [locale]);
}

/** The composer: text and/or up to 5 files; text at most 2,000 characters. */
export const MessageDraft = z.object({
  body: z.string().max(LIMITS.message, MSG.MESSAGE_TOO_LONG),
  attachments: z.array(z.object({ id: z.string() })).max(LIMITS.files, MSG.TOO_MANY_FILES),
}).refine(d => d.body.trim().length > 0 || d.attachments.length > 0, { path: ['body'], message: MSG.MESSAGE_REQUIRED });

export function draftProblem(body: string, attachments: { id: string }[]): string | undefined {
  const r = MessageDraft.safeParse({ body, attachments });
  return r.success ? undefined : r.error.issues[0]?.message;
}

/** A file the browser may upload: type and size (the server checks the bytes too). */
export function fileProblem(file: File): string | undefined {
  if (file.size === 0) return MSG.FILE_REQUIRED;
  if (!FILE_TYPES.includes(file.type.toLowerCase()) && !/\.(jpe?g|png|heic|heif|pdf)$/i.test(file.name)) return MSG.FILE_TYPE;
  if (file.size > LIMITS.fileBytes) return MSG.FILE_TOO_LARGE;
  return undefined;
}

export const CASE_TOPICS = ['verification', 'payouts', 'refunds', 'appointments', 'listings', 'account', 'api', 'safety', 'other'] as const;
export type CaseTopic = (typeof CASE_TOPICS)[number];
export const CHANNELS = ['chat', 'call', 'email'] as const;
export type Channel = (typeof CHANNELS)[number];

/** Help › Contact support. */
export const CaseForm = z.object({
  topic: z.enum(CASE_TOPICS, { message: MSG.TOPIC_REQUIRED }),
  related: z.string(),
  body: z.string().trim().min(1, MSG.CASE_BODY_REQUIRED).max(LIMITS.caseBody, MSG.CASE_BODY_TOO_LONG),
  channel: z.enum(CHANNELS, { message: MSG.CHANNEL_REQUIRED }),
  urgent: z.boolean(),
  attachments: z.array(z.object({ id: z.string() })).max(LIMITS.files, MSG.TOO_MANY_FILES),
});
export type CaseFormValues = { topic: CaseTopic | ''; related: string; body: string; channel: Channel; urgent: boolean; attachments: Attachment[] };

/** field → first message, for the form's inline errors. */
export function caseFormErrors(values: CaseFormValues): Record<string, string> {
  const r = CaseForm.safeParse(values);
  if (r.success) return {};
  const out: Record<string, string> = {};
  for (const i of r.error.issues) { const k = String(i.path[0]); out[k] ??= i.message; }
  return out;
}
