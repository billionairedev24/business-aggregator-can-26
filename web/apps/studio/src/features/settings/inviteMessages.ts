import { defineMessages } from '@northline/ui';

/** Team invitation accept page (not drawn in the design; copy recorded in docs/DECISIONS.md). */
export const useInviteT = defineMessages({
  en: {
    kicker: 'Team invitation', title: 'Join {business} on Northline', lede: 'You’ve been invited as {role}. You keep your own sign-in; the owner can change your role or remove you at any time.',
    role_owner: 'an owner', role_technician: 'a technician', role_bookkeeper: 'a bookkeeper', role_cook: 'a cook',
    join: 'Join {business}', joining: 'Joining…', switchAccount: 'Not you? Sign out',
    notForYou: 'This invitation was sent to a different email or mobile than {who}. Sign in with the invited account to accept it.',
    needsMfa: 'Business accounts need a passkey or authenticator app. Sign out, sign in with one, and open the link again.',
    alreadyMember: 'You’re already on this team.', acceptError: 'We couldn’t add you to the team. Try again.',
    state_expired: 'This invitation has expired. Ask the owner for a new one.', state_accepted: 'This invitation was already used.', state_revoked: 'This invitation was withdrawn.',
    invalidTitle: 'Invitation not found', invalid: 'This invitation link isn’t valid. Check the link or ask the owner for a new one.', loadError: 'We couldn’t load the invitation.',
  },
  fr: {
    kicker: 'Invitation d’équipe', title: 'Rejoindre {business} sur Northline', lede: 'Vous êtes invité comme {role}. Vous gardez votre propre connexion; le propriétaire peut changer votre rôle ou vous retirer en tout temps.',
    role_owner: 'propriétaire', role_technician: 'technicien', role_bookkeeper: 'comptable', role_cook: 'cuisinier',
    join: 'Rejoindre {business}', joining: 'Ajout…', switchAccount: 'Pas vous? Se déconnecter',
    notForYou: 'Cette invitation a été envoyée à un autre courriel ou mobile que {who}. Connectez-vous avec le compte invité pour l’accepter.',
    needsMfa: 'Les comptes d’entreprise exigent une clé d’accès ou une application d’authentification. Déconnectez-vous, reconnectez-vous avec l’une d’elles et rouvrez le lien.',
    alreadyMember: 'Vous faites déjà partie de cette équipe.', acceptError: 'Impossible de vous ajouter à l’équipe. Réessayez.',
    state_expired: 'Cette invitation a expiré. Demandez-en une nouvelle au propriétaire.', state_accepted: 'Cette invitation a déjà été utilisée.', state_revoked: 'Cette invitation a été retirée.',
    invalidTitle: 'Invitation introuvable', invalid: 'Ce lien d’invitation n’est pas valide. Vérifiez le lien ou demandez-en un nouveau au propriétaire.', loadError: 'Impossible de charger l’invitation.',
  },
});
