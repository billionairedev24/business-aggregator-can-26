import { defineMessages } from '@northline/ui';

/** S-156: the seller detail's standing card (consequences, lifting them, coaching plans). Ours. */
export const useSellerStandingT = defineMessages({
  en: {
    title: 'Consequences and coaching',
    loadError: 'This business’s consequences and coaching could not be loaded.',
    none: 'No consequence in force.',
    k_suspended: 'Suspended', k_search_hidden: 'Hidden from search', k_instant_book_off: 'Instant book paused', k_photo_delay: 'Payout released later', k_no_show_warning: 'No-show warning',
    by_rules: 'by the trust rules', by_staff: 'by staff', since: '{what} · {by} · since {date}',
    appeal: 'Appeal {number}: {state}',
    lift: 'Lift', liftTitle: 'Lift: {what}', liftReason: 'Reason (the business sees it)', liftCta: 'Lift it', cancel: 'Cancel',
    appeals: '{n, plural, one {# appeal} other {# appeals}}',
    coaching: 'Coaching plan', noCoaching: 'No coaching plan.', start: 'Start a coaching plan',
    tasksDone: '{done} of {total} tasks done', complete: 'Close as completed', cancelPlan: 'Cancel the plan',
    planTitle: 'Title', planNote: 'Note for the business', planDue: 'Due date', planTasks: 'Tasks · one per line', startCta: 'Start the plan',
    error: 'That didn’t work. Try again.',
  },
  fr: {
    title: 'Mesures et accompagnement',
    loadError: 'Impossible de charger les mesures et l’accompagnement de cette entreprise.',
    none: 'Aucune mesure en vigueur.',
    k_suspended: 'Suspendue', k_search_hidden: 'Masquée dans la recherche', k_instant_book_off: 'Réservation instantanée suspendue', k_photo_delay: 'Versement libéré plus tard', k_no_show_warning: 'Avertissement d’absence',
    by_rules: 'par les règles de confiance', by_staff: 'par l’équipe', since: '{what} · {by} · depuis le {date}',
    appeal: 'Appel {number} : {state}',
    lift: 'Lever', liftTitle: 'Lever : {what}', liftReason: 'Raison (l’entreprise la voit)', liftCta: 'La lever', cancel: 'Annuler',
    appeals: '{n, plural, one {# appel} other {# appels}}',
    coaching: 'Plan d’accompagnement', noCoaching: 'Aucun plan d’accompagnement.', start: 'Commencer un plan d’accompagnement',
    tasksDone: '{done} tâches sur {total} faites', complete: 'Fermer comme terminé', cancelPlan: 'Annuler le plan',
    planTitle: 'Titre', planNote: 'Note pour l’entreprise', planDue: 'Échéance', planTasks: 'Tâches · une par ligne', startCta: 'Commencer le plan',
    error: 'Cela n’a pas fonctionné. Réessayez.',
  },
});
export type SellerStandingKey = Parameters<ReturnType<typeof useSellerStandingT>>[0];
