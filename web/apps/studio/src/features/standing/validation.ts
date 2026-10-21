import { z } from 'zod';

/** The api's rules for an appeal (ca.northline.standing.domain.AppealRules), with its exact messages. */
export const STATEMENT_MAX = 2000;
export const FILES_MAX = 5;
export const FILE_MAX_BYTES = 10 * 1024 * 1024;
export const FILE_TYPES = ['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'application/pdf'];

export const AppealForm = z.object({
  statement: z.string().trim().min(1, 'Tell us why the decision should change.').max(STATEMENT_MAX, 'Keep your appeal under 2,000 characters.'),
  files: z.array(z.custom<File>())
    .max(FILES_MAX, 'You can attach up to 5 files.')
    .refine(fs => fs.every(f => FILE_TYPES.includes(f.type)), 'Upload a photo (JPG, PNG, HEIC) or a PDF.')
    .refine(fs => fs.every(f => f.size <= FILE_MAX_BYTES), 'Files can be up to 10 MB.'),
});

export const firstErrors = (r: ReturnType<typeof AppealForm.safeParse>): Partial<Record<'statement' | 'files', string>> =>
  r.success ? {} : Object.fromEntries([...r.error.issues].reverse().map(i => [String(i.path[0]), i.message]));

/** French of the messages above, as the api words them (docs/spec/validation-messages.fr-CA.tsv). */
export const FR: Record<string, string> = {
  'Tell us why the decision should change.': 'Dites-nous pourquoi la décision devrait changer.',
  'Keep your appeal under 2,000 characters.': 'Gardez votre appel sous 2 000 caractères.',
  'You can attach up to 5 files.': 'Vous pouvez joindre jusqu’à 5 fichiers.',
  'Upload a photo (JPG, PNG, HEIC) or a PDF.': 'Téléversez une photo (JPG, PNG, HEIC) ou un PDF.',
  'Files can be up to 10 MB.': 'Les fichiers peuvent faire jusqu’à 10 Mo.',
};
