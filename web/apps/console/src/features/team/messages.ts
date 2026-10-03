import { defineMessages } from '@northline/ui';

/** Team, roles & audit (S-96, design 03 lines 440–449). en = the design's copy; fr-CA after design/i18n-fr.js. */
export const useTeamT = defineMessages({
  en: {
    kicker: 'Team, roles & audit',
    title: 'Who can do what, and who did what',
    rolesTitle: 'Roles', invite: 'Invite',
    roleEntity: 'role', rolePlural: 'roles', c_role: 'Role', c_people: 'People', c_can: 'Can', c_needs: 'Needs',
    allScreens: 'Everything', needs: 'Second factor at sign-in',
    peopleTitle: 'People', personEntity: 'person', personPlural: 'people', c_name: 'Name', c_email: 'Email', c_roles: 'Roles',
    manage: 'Manage roles',
    auditTitle: 'Audit log', auditToday: 'Audit log · today',
    f_action: 'Action', f_actor: 'Who', f_business: 'Business id', f_from: 'From', f_to: 'To', anyone: 'Anyone', anyAction: 'Every action',
    a_console: 'Console (roles, on-call)', a_merchant: 'Businesses', a_payments: 'Payments', a_region: 'Provinces', a_golive: 'Go-live', a_catalogue: 'Catalogue', a_trust: 'Trust & safety',
    a_support: 'Support', a_api_key: 'API keys', a_fulfilment: 'Delivery',
    older: 'Older entries', newest: 'Back to newest', noEntries: 'No entry matches.',
    entry: '{who} ({role}) · {action}', entryTarget: '{target}', entryBusiness: '{business}', system: 'Northline (automatic)',
    inviteTitle: 'Invite to the console', f_email: 'Their email', f_role: 'Role', grant: 'Add role', cancel: 'Cancel', close: 'Close',
    inviteNote: 'They need a Northline account first. The role reaches them at their next sign-in or within 10 minutes.',
    manageTitle: 'Roles · {name}', remove: 'Remove {role}', addRole: 'Add a role',
    cannot: 'Only admins change roles.',
    loadError: "The team couldn't load. Try again.",
  },
  fr: {
    kicker: 'Équipe, rôles et audit',
    title: 'Qui peut faire quoi, et qui a fait quoi',
    rolesTitle: 'Rôles', invite: 'Inviter',
    roleEntity: 'rôle', rolePlural: 'rôles', c_role: 'Rôle', c_people: 'Personnes', c_can: 'Peut', c_needs: 'Exige',
    allScreens: 'Tout', needs: 'Deuxième facteur à la connexion',
    peopleTitle: 'Personnes', personEntity: 'personne', personPlural: 'personnes', c_name: 'Nom', c_email: 'Courriel', c_roles: 'Rôles',
    manage: 'Gérer les rôles',
    auditTitle: 'Journal d’audit', auditToday: 'Journal d’audit · aujourd’hui',
    f_action: 'Action', f_actor: 'Qui', f_business: 'Identifiant d’entreprise', f_from: 'Du', f_to: 'Au', anyone: 'Tout le monde', anyAction: 'Toutes les actions',
    a_console: 'Console (rôles, garde)', a_merchant: 'Entreprises', a_payments: 'Paiements', a_region: 'Provinces', a_golive: 'Mise en service', a_catalogue: 'Catalogue', a_trust: 'Confiance et sécurité',
    a_support: 'Soutien', a_api_key: 'Clés d’API', a_fulfilment: 'Livraison',
    older: 'Entrées plus anciennes', newest: 'Revenir aux plus récentes', noEntries: 'Aucune entrée ne correspond.',
    entry: '{who} ({role}) · {action}', entryTarget: '{target}', entryBusiness: '{business}', system: 'Northline (automatique)',
    inviteTitle: 'Inviter à la console', f_email: 'Son courriel', f_role: 'Rôle', grant: 'Ajouter le rôle', cancel: 'Annuler', close: 'Fermer',
    inviteNote: 'La personne doit d’abord avoir un compte Northline. Le rôle s’applique à sa prochaine connexion ou d’ici 10 minutes.',
    manageTitle: 'Rôles · {name}', remove: 'Retirer {role}', addRole: 'Ajouter un rôle',
    cannot: 'Seuls les administrateurs modifient les rôles.',
    loadError: 'L’équipe n’a pas pu être chargée. Réessayez.',
  },
});
export type TeamT = ReturnType<typeof useTeamT>;
export type TeamKey = Parameters<TeamT>[0];
