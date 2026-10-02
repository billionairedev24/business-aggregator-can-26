import { defineMessages } from '@northline/ui';

/** My profile (S-96, design 03 `profile`). en = the design's copy; fr-CA after design/i18n-fr.js. */
export const useProfileT = defineMessages({
  en: {
    title: 'My profile',
    meta: '{email} · roles: {roles} · viewing as {view}',
    tab_security: 'Security', tab_sessions: 'Devices & sessions', tab_audit: 'My audit trail', tab_prefs: 'Preferences', tabs: 'Profile sections',
    passkey: 'Passkey · {label}', primary: 'Primary', backup: 'Backup', authenticator: 'Authenticator app', on: 'On', off: 'Off',
    backupCodes: 'Backup codes', codesLeft: '{n} of 10 unused',
    addPasskey: 'Add passkey', signOutEverywhere: 'Sign out everywhere', removePasskey: 'Remove',
    keyLabel: 'Console passkey', keyAdded: 'Passkey added.', keyCancelled: 'Adding the passkey was cancelled.', keyUnsupported: "This browser can't create passkeys.",
    signedOutOthers: '{n, plural, one {# other session signed out.} other {# other sessions signed out.}}',
    err_step_up_required: 'Confirm with your passkey or authenticator code first: sign out and back in, then try again within 10 minutes.',
    err_last_factor: "That's your last second factor. Add another before removing it.",
    err_current_session: 'That is this browser. Use Sign out instead.',
    err_not_found: 'It was already removed.', err_rate_limited: 'Too many tries. Wait a minute.', err_signed_out: 'Your sign-in expired. Sign in again.',
    err_failed: "That didn't work. Try again.",
    confirmFirst: 'For your security, these settings need a recent second factor. Sign out and back in with your passkey or authenticator, then come back.',
    sessionsEntity: 'session', device: 'Device', where: 'Where', lastActive: 'Last active', now: 'Now', thisDevice: 'This browser', signOut: 'Sign out',
    unknown: 'Unknown device',
    language: 'Language', english: 'English', french: 'Français',
    loadError: "Your security settings couldn't load. Try again.",
  },
  fr: {
    title: 'Mon profil',
    meta: '{email} · rôles : {roles} · vue : {view}',
    tab_security: 'Sécurité', tab_sessions: 'Appareils et sessions', tab_audit: 'Mon historique d’audit', tab_prefs: 'Préférences', tabs: 'Sections du profil',
    passkey: 'Clé d’accès · {label}', primary: 'Principale', backup: 'Secours', authenticator: 'Application d’authentification', on: 'Activée', off: 'Désactivée',
    backupCodes: 'Codes de secours', codesLeft: '{n} sur 10 inutilisés',
    addPasskey: 'Ajouter une clé d’accès', signOutEverywhere: 'Se déconnecter partout', removePasskey: 'Retirer',
    keyLabel: 'Clé d’accès console', keyAdded: 'Clé d’accès ajoutée.', keyCancelled: 'L’ajout de la clé d’accès a été annulé.', keyUnsupported: 'Ce navigateur ne peut pas créer de clé d’accès.',
    signedOutOthers: '{n, plural, one {# autre session déconnectée.} other {# autres sessions déconnectées.}}',
    err_step_up_required: 'Confirmez d’abord avec votre clé d’accès ou votre code d’authentification : déconnectez-vous puis reconnectez-vous, et réessayez dans les 10 minutes.',
    err_last_factor: 'C’est votre dernier deuxième facteur. Ajoutez-en un autre avant de le retirer.',
    err_current_session: 'C’est ce navigateur. Utilisez plutôt Se déconnecter.',
    err_not_found: 'Il a déjà été retiré.', err_rate_limited: 'Trop d’essais. Attendez une minute.', err_signed_out: 'Votre connexion a expiré. Reconnectez-vous.',
    err_failed: 'Cela n’a pas fonctionné. Réessayez.',
    confirmFirst: 'Pour votre sécurité, ces réglages exigent un deuxième facteur récent. Déconnectez-vous puis reconnectez-vous avec votre clé d’accès ou votre application d’authentification, puis revenez.',
    sessionsEntity: 'session', device: 'Appareil', where: 'Lieu', lastActive: 'Dernière activité', now: 'Maintenant', thisDevice: 'Ce navigateur', signOut: 'Déconnecter',
    unknown: 'Appareil inconnu',
    language: 'Langue', english: 'English', french: 'Français',
    loadError: 'Vos réglages de sécurité n’ont pas pu être chargés. Réessayez.',
  },
});
export type ProfileT = ReturnType<typeof useProfileT>;
export type ProfileKey = Parameters<ProfileT>[0];
