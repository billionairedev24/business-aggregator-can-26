import { defineMessages } from '@northline/ui';

/** S-158: enrol an existing business into a market's pilot (S-120 had the api only; our copy). */
export const useEnrolT = defineMessages({
  en: {
    enrol: 'Enrol a business', title: 'Enrol an existing business',
    lede: 'For a business already on {brand}: it joins the market’s pilot board, and stays out of search until the market goes live.',
    search: 'Find the business', searchHint: 'Its name, at least 2 letters.', noMatch: 'No business matches.', pick: 'Choose {name}',
    picked: 'Chosen: {name}', market: 'Pilot market', cancel: 'Cancel', confirm: 'Enrol', enrolled: '{name} joined the pilot.',
    already: 'This business is already in that pilot.', failed: 'That didn’t work. Try again.', loading: 'Searching…',
  },
  fr: {
    enrol: 'Inscrire une entreprise', title: 'Inscrire une entreprise existante',
    lede: 'Pour une entreprise déjà sur {brand} : elle rejoint le tableau du projet pilote du marché et reste hors de la recherche jusqu’à l’ouverture du marché.',
    search: 'Trouver l’entreprise', searchHint: 'Son nom, au moins 2 lettres.', noMatch: 'Aucune entreprise ne correspond.', pick: 'Choisir {name}',
    picked: 'Choisie : {name}', market: 'Marché pilote', cancel: 'Annuler', confirm: 'Inscrire', enrolled: '{name} s’est jointe au projet pilote.',
    already: 'Cette entreprise fait déjà partie de ce projet pilote.', failed: 'Cela n’a pas fonctionné. Réessayez.', loading: 'Recherche…',
  },
});
