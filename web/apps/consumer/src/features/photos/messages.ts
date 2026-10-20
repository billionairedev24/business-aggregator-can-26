import { defineMessages } from '@northline/ui';

/** Photos of the job (S-153) — the api's messages, word for word (docs/spec/validation-messages.fr-CA.tsv). */
export const usePhotosT = defineMessages({
  en: {
    title: 'Photos (optional)',
    hint: 'A photo of the problem helps the provider. Up to 6 photos, 10 MB each. Only the providers you ask see them.',
    add: 'Add a photo', adding: 'Uploading…', remove: 'Remove {name}', photoAlt: 'Your photo {n}',
    type: 'Add a photo (JPEG, PNG, HEIC or WebP).', size: 'Photos can be up to 10 MB.', count: 'Add up to 6 photos.',
    failed: 'The photo couldn’t be uploaded. Try again.',
    yours: 'Your photos',
  },
  fr: {
    title: 'Photos (facultatif)',
    hint: 'Une photo du problème aide le prestataire. Jusqu’à 6 photos, 10 Mo chacune. Seuls les prestataires sollicités les voient.',
    add: 'Ajouter une photo', adding: 'Téléversement…', remove: 'Retirer {name}', photoAlt: 'Votre photo {n}',
    type: 'Ajoutez une photo (JPEG, PNG, HEIC ou WebP).', size: 'Les photos peuvent faire jusqu’à 10 Mo.', count: 'Ajoutez jusqu’à 6 photos.',
    failed: 'La photo n’a pas pu être téléversée. Réessayez.',
    yours: 'Vos photos',
  },
});
