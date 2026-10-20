import { defineMessages } from '@northline/ui';

/** S-158: API & webhooks › Partners (our copy; design 03 shows partner keys only). */
export const usePartnersT = defineMessages({
  en: {
    title: 'Partners', lede: 'Apps that call the API for businesses (client credentials with a signed JWT). A business is reached only once its owner approves in the Studio.',
    register: 'Register partner', entity: 'partner', plural: 'partners', empty: 'No partner is registered here yet.', loadError: 'The partners couldn’t load. Try again.',
    c_partner: 'Partner', c_client: 'Client id', c_scopes: 'Scopes', c_businesses: 'Businesses', c_keys: 'Keys', c_state: 'Status',
    st_active: 'Active', st_disabled: 'Disabled', keysV: 'Version {v}{rotated, select, none {} other { · rotated {rotated}}}',
    bizCount: '{approved} approved · {requested} waiting', rotate: 'Rotate keys', disable: 'Disable', enable: 'Enable', manage: 'Businesses',
    registerTitle: 'Register a partner', f_name: 'Name (client id)', f_nameHint: 'partner:{name} · lower-case letters, digits and dashes',
    f_display: 'Display name', f_jwks: 'JWK Set URL', f_jwksHint: 'Where the partner publishes its public keys (HTTPS).',
    f_scopes: 'Scopes', scope_api_read: 'api.read — read listings and orders', scope_api_write: 'api.write — change them',
    f_ttl: 'Token lifetime (minutes)', f_businesses: 'Business ids', f_businessesHint: 'One per line. Each owner is asked to approve.',
    cancel: 'Cancel', create: 'Register', save: 'Save', done: 'Done',
    rotateTitle: 'Rotate the keys of {name}', rotateBody: 'The partner’s next token request reads its JWK Set again. Give a new address only if the partner moved it.',
    f_newJwks: 'New JWK Set URL (optional)', confirmRotate: 'Rotate keys',
    disableTitle: 'Disable {name}?', disableBody: 'No new token from now on. Tokens already issued end within their lifetime ({minutes} min).', confirmDisable: 'Disable partner',
    bizTitle: 'Businesses of {name}', bizAsk: 'Ask more businesses', ask: 'Ask', remove: 'Remove', removeLabel: 'Remove {business}',
    b_requested: 'Waiting for the owner', b_approved: 'Approved', b_declined: 'Declined', b_revoked: 'Revoked',
    cannot: 'Only admins register or change partners.', exists: 'A partner with this name already exists.', failed: 'That didn’t work. Try again.',
  },
  fr: {
    title: 'Partenaires', lede: 'Applications qui appellent l’API pour des entreprises (identifiants client avec un JWT signé). Une entreprise n’est accessible qu’une fois que son propriétaire a approuvé dans le Studio.',
    register: 'Inscrire un partenaire', entity: 'partenaire', plural: 'partenaires', empty: 'Aucun partenaire n’est encore inscrit ici.', loadError: 'Impossible de charger les partenaires. Réessayez.',
    c_partner: 'Partenaire', c_client: 'Identifiant client', c_scopes: 'Portées', c_businesses: 'Entreprises', c_keys: 'Clés', c_state: 'Statut',
    st_active: 'Actif', st_disabled: 'Désactivé', keysV: 'Version {v}{rotated, select, none {} other { · renouvelées {rotated}}}',
    bizCount: '{approved} approuvée(s) · {requested} en attente', rotate: 'Renouveler les clés', disable: 'Désactiver', enable: 'Réactiver', manage: 'Entreprises',
    registerTitle: 'Inscrire un partenaire', f_name: 'Nom (identifiant client)', f_nameHint: 'partner:{name} · lettres minuscules, chiffres et tirets',
    f_display: 'Nom affiché', f_jwks: 'URL du JWK Set', f_jwksHint: 'Où le partenaire publie ses clés publiques (HTTPS).',
    f_scopes: 'Portées', scope_api_read: 'api.read — lire les annonces et commandes', scope_api_write: 'api.write — les modifier',
    f_ttl: 'Durée du jeton (minutes)', f_businesses: 'Identifiants d’entreprise', f_businessesHint: 'Un par ligne. Chaque propriétaire doit approuver.',
    cancel: 'Annuler', create: 'Inscrire', save: 'Enregistrer', done: 'Terminé',
    rotateTitle: 'Renouveler les clés de {name}', rotateBody: 'La prochaine demande de jeton du partenaire relit son JWK Set. Donnez une nouvelle adresse seulement si le partenaire l’a déplacé.',
    f_newJwks: 'Nouvelle URL du JWK Set (facultatif)', confirmRotate: 'Renouveler les clés',
    disableTitle: 'Désactiver {name}?', disableBody: 'Plus aucun nouveau jeton à partir de maintenant. Les jetons déjà émis prennent fin à leur échéance ({minutes} min).', confirmDisable: 'Désactiver le partenaire',
    bizTitle: 'Entreprises de {name}', bizAsk: 'Demander à d’autres entreprises', ask: 'Demander', remove: 'Retirer', removeLabel: 'Retirer {business}',
    b_requested: 'En attente du propriétaire', b_approved: 'Approuvée', b_declined: 'Refusée', b_revoked: 'Révoquée',
    cannot: 'Seuls les administrateurs inscrivent ou modifient les partenaires.', exists: 'Un partenaire porte déjà ce nom.', failed: 'Cela n’a pas fonctionné. Réessayez.',
  },
});
export type PartnersT = ReturnType<typeof usePartnersT>;
