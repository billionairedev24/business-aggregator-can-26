import { defineMessages } from '@northline/ui';

/** S-158: Applications › Registry checks (our copy; the design has no drawing of this queue). */
export const useRegistryT = defineMessages({
  en: {
    tab: 'Registry checks',
    sub: '{n, plural, =0 {No lookup waits for an agent.} one {# lookup waits for an agent.} other {# lookups wait for an agent.}}',
    entity: 'registry check', plural: 'registry checks', empty: 'No registry lookup needs a human right now.',
    loadError: 'We couldn’t load the registry checks.',
    colBusiness: 'Business', colCheck: 'Check', colNumber: 'Number', colSource: 'Source', colFound: 'What the registry says', colOutcome: 'Outcome', colChecked: 'Checked',
    src_corporations_canada: 'Corporations Canada', src_alberta_corporate_registry: 'Provincial corporate registry', src_calgary_business_licences: 'Municipal business licences', src_manual: 'Agent (no API)',
    out_matched: 'Matched', out_mismatch: 'Doesn’t match', out_not_found: 'Not found', out_manual: 'Needs an agent', out_unavailable: 'Source down',
    rsn_name: 'name', rsn_status: 'status', rsn_expired: 'expired',
    outcomeWith: '{outcome} · {reasons}', expected: 'Entered: {name}', record: '{name} · {status}', noRecord: 'No record',
    recheck: 'Re-check', approve: 'Approve', reject: 'Reject',
    recheckDone: '{business}: looked up again — {outcome}.', recheckVerified: '{business}: the registry matches now. The check is verified.',
    decideTitle: '{decision} the {check} check of {business}', reference: 'What you checked', referenceHint: 'Registry agent search, licence number…',
    expiresOn: 'Expires on', expiresHint: 'The licence’s expiry, when you saw one.', note: 'Note', noteHint: 'For the record (at most 500 characters).',
    cancel: 'Cancel', confirmApprove: 'Approve the check', confirmReject: 'Reject the check',
    approved: '{business}: the check is approved.', rejected: '{business}: the check is rejected; the owner is asked to fix it.',
    closed: 'Someone else already decided this lookup.', nothingToLookUp: 'There is no number to look up for this check.', failed: 'That didn’t work. Try again.',
  },
  fr: {
    tab: 'Vérifications au registre',
    sub: '{n, plural, =0 {Aucune recherche n’attend un agent.} one {# recherche attend un agent.} other {# recherches attendent un agent.}}',
    entity: 'vérification au registre', plural: 'vérifications au registre', empty: 'Aucune recherche au registre n’a besoin d’une personne pour l’instant.',
    loadError: 'Impossible de charger les vérifications au registre.',
    colBusiness: 'Entreprise', colCheck: 'Vérification', colNumber: 'Numéro', colSource: 'Source', colFound: 'Ce que dit le registre', colOutcome: 'Résultat', colChecked: 'Vérifié',
    src_corporations_canada: 'Corporations Canada', src_alberta_corporate_registry: 'Registre provincial des sociétés', src_calgary_business_licences: 'Permis d’affaires municipaux', src_manual: 'Agent (sans API)',
    out_matched: 'Concorde', out_mismatch: 'Ne concorde pas', out_not_found: 'Introuvable', out_manual: 'Agent requis', out_unavailable: 'Source indisponible',
    rsn_name: 'nom', rsn_status: 'statut', rsn_expired: 'expiré',
    outcomeWith: '{outcome} · {reasons}', expected: 'Saisi : {name}', record: '{name} · {status}', noRecord: 'Aucun dossier',
    recheck: 'Revérifier', approve: 'Approuver', reject: 'Refuser',
    recheckDone: '{business} : nouvelle recherche — {outcome}.', recheckVerified: '{business} : le registre concorde maintenant. La vérification est confirmée.',
    decideTitle: '{decision} la vérification {check} de {business}', reference: 'Ce que vous avez vérifié', referenceHint: 'Recherche d’un agent d’enregistrement, numéro de permis…',
    expiresOn: 'Expire le', expiresHint: 'L’échéance du permis, si vous l’avez vue.', note: 'Note', noteHint: 'Pour le dossier (500 caractères au plus).',
    cancel: 'Annuler', confirmApprove: 'Approuver la vérification', confirmReject: 'Refuser la vérification',
    approved: '{business} : la vérification est approuvée.', rejected: '{business} : la vérification est refusée; on demande au propriétaire de la corriger.',
    closed: 'Quelqu’un d’autre a déjà tranché cette recherche.', nothingToLookUp: 'Il n’y a aucun numéro à rechercher pour cette vérification.', failed: 'Cela n’a pas fonctionné. Réessayez.',
  },
});
export type RegistryT = ReturnType<typeof useRegistryT>;
