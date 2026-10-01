import { defineMessages } from '@northline/ui';

/** On-call & escalations (S-96, design 03 lines 451–455). en = the design's copy; fr-CA after design/i18n-fr.js. */
export const useOncallT = defineMessages({
  en: {
    kicker: 'On-call & escalations',
    title: "Who's on, how to reach them, and what's burning",
    rotaTitle: 'Rota · today', onCall: 'on call', you: '{name} (you)', when: '{from} → {to}',
    swap: 'Swap a shift', addShift: 'Add shift', remove: 'Remove',
    noShifts: 'No shift planned for the next week.',
    escalationTitle: 'Escalation paths',
    e1: 'Live order failing / courier unreachable', p1: 'Dispatcher on call → Ops lead in 15 min',
    e2: 'Payment stuck / escrow error', p2: 'Finance on call → Stripe support (P1)',
    e3: "Safety incident at a customer's home", p3: 'T&S lead immediately → legal within 1 h',
    e4: 'Province flag misfire', p4: 'Two admins → rollback flag',
    e5: 'Data / privacy request (PIPEDA)', p5: 'Privacy officer · 30-day clock',
    targets: 'Targets: acknowledge urgent pages in 5 min, customer-facing incidents posted to status within 15 min, post-mortem within 5 business days.',
    swapTitle: 'Hand over · {duty}', f_to: 'To', f_shift: 'Shift', handOver: 'Hand over',
    addTitle: 'Add a shift', f_who: 'Who', f_starts: 'Starts', f_ends: 'Ends', f_duty: 'What they answer for', add: 'Add', cancel: 'Cancel',
    loadError: "The rota couldn't load. Try again.",
  },
  fr: {
    kicker: 'Garde et escalades',
    title: 'Qui est de garde, comment le joindre, et ce qui brûle',
    rotaTitle: 'Horaire · aujourd’hui', onCall: 'de garde', you: '{name} (vous)', when: '{from} → {to}',
    swap: 'Échanger un quart', addShift: 'Ajouter un quart', remove: 'Retirer',
    noShifts: 'Aucun quart prévu pour la semaine qui vient.',
    escalationTitle: 'Chemins d’escalade',
    e1: 'Commande en direct en échec / livreur injoignable', p1: 'Répartiteur de garde → responsable des opérations en 15 min',
    e2: 'Paiement bloqué / erreur de fiducie', p2: 'Finances de garde → soutien Stripe (P1)',
    e3: 'Incident de sécurité chez un client', p3: 'Responsable C et S immédiatement → juridique en moins d’une heure',
    e4: 'Erreur d’indicateur de province', p4: 'Deux administrateurs → annuler l’indicateur',
    e5: 'Demande de données / vie privée (LPRPDE)', p5: 'Responsable de la vie privée · délai de 30 jours',
    targets: 'Cibles : accuser réception des alertes urgentes en 5 min, publier les incidents visibles par les clients sur la page d’état en 15 min, post-mortem en 5 jours ouvrables.',
    swapTitle: 'Céder · {duty}', f_to: 'À', f_shift: 'Quart', handOver: 'Céder',
    addTitle: 'Ajouter un quart', f_who: 'Qui', f_starts: 'Début', f_ends: 'Fin', f_duty: 'Ce dont la personne répond', add: 'Ajouter', cancel: 'Annuler',
    loadError: 'L’horaire n’a pas pu être chargé. Réessayez.',
  },
});
export type OncallT = ReturnType<typeof useOncallT>;
