import { defineMessages } from '@northline/ui';

/** S-158: help triage in the help centre (S-132's api; our copy). The server's 422 texts as in the S-40 catalogue. */
export const useTriageT = defineMessages({
  en: {
    title: 'Not sure where to start?', lede: 'Tell us what happened. {brand}’s AI suggests the right place — nothing is sent to anyone until you open a case.',
    label: 'What happened?', placeholder: 'For example: my order arrived without the bread I paid for',
    suggest: 'Suggest where to start', suggesting: 'Reading…', guest: 'Sign in to get a suggestion for your order or booking.', signIn: 'Sign in',
    tooShort: 'Tell us a little more (at least 10 characters).', tooLong: 'Keep it under 2,000 characters.',
    unavailable: 'Suggestions aren’t available right now. Choose a topic below, or open a case in Help & cases.',
    failed: 'We couldn’t make a suggestion. Choose a topic below.',
    looksLike: 'This looks like: {category}', aiNote: 'A suggestion from AI. You choose what to open.',
    urgent: 'If anyone is in danger, call emergency services first. Then tell us — urgent cases go to the front of the queue.',
    r_refund_request: 'Start a refund request from the order: open it in Orders & bookings and choose “Something’s wrong”.',
    r_dispute: 'Report it from the order or booking in Orders & bookings: the business answers first, then our team decides if needed.',
    r_support: 'Open a help case in Help & cases and our team answers you there.',
    toOrders: 'Go to Orders & bookings', toCases: 'Open Help & cases', read: 'Read: {topic}',
    cat_missing_item: 'Something missing', cat_wrong_item: 'Wrong item', cat_damaged: 'Damaged', cat_not_as_described: 'Not as described',
    cat_late: 'Late', cat_not_delivered: 'Not delivered', cat_service_not_done: 'Service not done', cat_service_quality: 'Service quality',
    cat_no_show: 'No-show', cat_billing: 'Billing', cat_safety: 'Safety', cat_account: 'Your account', cat_other: 'Something else',
  },
  fr: {
    title: 'Vous ne savez pas par où commencer?', lede: 'Dites-nous ce qui s’est passé. L’IA de {brand} vous suggère le bon endroit — rien n’est envoyé à qui que ce soit avant que vous ouvriez une demande.',
    label: 'Que s’est-il passé?', placeholder: 'Par exemple : ma commande est arrivée sans le pain que j’ai payé',
    suggest: 'Suggérer par où commencer', suggesting: 'Lecture…', guest: 'Connectez-vous pour obtenir une suggestion pour votre commande ou réservation.', signIn: 'Se connecter',
    tooShort: 'Dites-nous-en un peu plus (au moins 10 caractères).', tooLong: 'Gardez-le sous 2 000 caractères.',
    unavailable: 'Les suggestions ne sont pas disponibles pour l’instant. Choisissez un sujet ci-dessous ou ouvrez une demande dans Aide et dossiers.',
    failed: 'Impossible de faire une suggestion. Choisissez un sujet ci-dessous.',
    looksLike: 'Cela ressemble à : {category}', aiNote: 'Une suggestion de l’IA. C’est vous qui choisissez quoi ouvrir.',
    urgent: 'Si quelqu’un est en danger, appelez d’abord les services d’urgence. Puis écrivez-nous — les cas urgents passent en tête de file.',
    r_refund_request: 'Demandez un remboursement à partir de la commande : ouvrez-la dans Commandes et réservations et choisissez « Un problème ».',
    r_dispute: 'Signalez-le à partir de la commande ou de la réservation dans Commandes et réservations : l’entreprise répond d’abord, puis notre équipe tranche au besoin.',
    r_support: 'Ouvrez une demande dans Aide et dossiers; notre équipe vous y répond.',
    toOrders: 'Aller à Commandes et réservations', toCases: 'Ouvrir Aide et dossiers', read: 'Lire : {topic}',
    cat_missing_item: 'Article manquant', cat_wrong_item: 'Mauvais article', cat_damaged: 'Endommagé', cat_not_as_described: 'Non conforme à la description',
    cat_late: 'En retard', cat_not_delivered: 'Non livré', cat_service_not_done: 'Service non effectué', cat_service_quality: 'Qualité du service',
    cat_no_show: 'Absence', cat_billing: 'Facturation', cat_safety: 'Sécurité', cat_account: 'Votre compte', cat_other: 'Autre chose',
  },
});
export type TriageT = ReturnType<typeof useTriageT>;
export type TriageKey = Parameters<TriageT>[0];
