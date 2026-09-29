import { useCallback } from 'react';
import { z } from 'zod';
import { useLocale } from '@northline/ui';

/**
 * Reply / report validation — identical to the server's (ca.northline.trust.domain.ReviewRules). validation-rules.md
 * has no reviews section; see DECISIONS.md.
 */
export const MSG = {
  REPLY_REQUIRED: 'Write a reply before sending.',
  REPLY_TOO_LONG: 'Keep replies under 1,000 characters.',
  REASON_REQUIRED: 'Choose a reason.',
  NOTE_REQUIRED: "Tell us what's wrong with this review.",
  NOTE_TOO_LONG: 'Keep the note under 500 characters.',
} as const;

const FR: Record<string, string> = {
  [MSG.REPLY_REQUIRED]: 'Écrivez une réponse avant de l’envoyer.',
  [MSG.REPLY_TOO_LONG]: 'Les réponses doivent faire moins de 1 000 caractères.',
  [MSG.REASON_REQUIRED]: 'Choisissez une raison.',
  [MSG.NOTE_REQUIRED]: 'Dites-nous ce qui ne va pas avec cet avis.',
  [MSG.NOTE_TOO_LONG]: 'La note doit faire moins de 500 caractères.',
};

export function useMessageT() {
  const { locale } = useLocale();
  return useCallback((message: string | undefined) => (message && locale === 'fr' ? FR[message] ?? message : message), [locale]);
}

export const ReplyForm = z.object({ text: z.string().trim().min(1, MSG.REPLY_REQUIRED).max(1000, MSG.REPLY_TOO_LONG) });

export const ReportForm = z.object({
  reason: z.enum(['fake', 'offensive', 'personal_info', 'wrong_business', 'other'], { message: MSG.REASON_REQUIRED }),
  note: z.string().max(500, MSG.NOTE_TOO_LONG),
}).refine(v => v.reason !== 'other' || v.note.trim().length > 0, { path: ['note'], message: MSG.NOTE_REQUIRED });

export function firstErrors(result: { success: boolean; error?: z.ZodError }): Record<string, string> {
  const out: Record<string, string> = {};
  for (const i of result.error?.issues ?? []) out[String(i.path[0])] ??= i.message;
  return out;
}
