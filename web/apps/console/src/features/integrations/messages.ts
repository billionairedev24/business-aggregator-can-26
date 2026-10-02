import { defineMessages } from '@northline/ui';

/** API & webhooks (S-96, design 03 lines 383–399). en = the design's copy; fr-CA after design/i18n-fr.js. */
export const useIntegrationsT = defineMessages({
  en: {
    kicker: 'Headless API & webhooks',
    title: "Every screen you've seen runs on this API.",
    lede: 'REST (OpenAPI 3.1) for everything and HMAC-signed webhooks. Keys are scoped per seller; each has a rate limit.',
    keysTitle: 'Partner keys', issue: 'Issue key', revoke: 'Revoke',
    entity: 'API client', plural: 'API clients', c_owner: 'Owner', c_scopes: 'Scopes', c_used: 'Last used', c_status: 'Status',
    owner: '{business} · {name}', active: 'Active', revoked: 'Revoked', never: 'Never',
    issueTitle: 'Issue a key for a business', f_business: 'Business id', f_name: 'Name', f_scopes: 'Scopes', cancel: 'Cancel', create: 'Issue',
    secretTitle: 'Copy the key now', secretNote: 'This is the only time the key is shown. Give it to the business over a secure channel.', done: 'Done',
    revokeTitle: 'Revoke {name}?', revokeNote: 'Calls with this key stop at once. The business is told in its audit log.', confirmRevoke: 'Revoke key',
    cannot: 'Only admins issue or revoke keys.',
    loadError: "The keys couldn't load. Try again.",
  },
  fr: {
    kicker: 'API sans interface et webhooks',
    title: 'Chaque écran que vous avez vu repose sur cette API.',
    lede: 'REST (OpenAPI 3.1) pour tout et des webhooks signés HMAC. Les clés sont limitées par vendeur; chacune a une limite de débit.',
    keysTitle: 'Clés partenaires', issue: 'Émettre une clé', revoke: 'Révoquer',
    entity: 'client API', plural: 'clients API', c_owner: 'Titulaire', c_scopes: 'Portées', c_used: 'Dernière utilisation', c_status: 'Statut',
    owner: '{business} · {name}', active: 'Active', revoked: 'Révoquée', never: 'Jamais',
    issueTitle: 'Émettre une clé pour une entreprise', f_business: 'Identifiant d’entreprise', f_name: 'Nom', f_scopes: 'Portées', cancel: 'Annuler', create: 'Émettre',
    secretTitle: 'Copiez la clé maintenant', secretNote: 'La clé n’est affichée qu’une fois. Transmettez-la à l’entreprise par un canal sécurisé.', done: 'Terminé',
    revokeTitle: 'Révoquer {name}?', revokeNote: 'Les appels avec cette clé cessent aussitôt. L’entreprise le voit dans son journal d’audit.', confirmRevoke: 'Révoquer la clé',
    cannot: 'Seuls les administrateurs émettent ou révoquent des clés.',
    loadError: 'Les clés n’ont pas pu être chargées. Réessayez.',
  },
});
