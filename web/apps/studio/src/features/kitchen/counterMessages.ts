import { defineMessages } from '@northline/ui';

/** The kitchen display's ID check for age-restricted pickups (2026-10-04). Ours; en + fr-CA. */
export const useCounterT = defineMessages({
  en: {
    title: 'Check ID · {ref}',
    lede: 'This order has age-restricted items. Hand it over only to {name}, {age} or older, after checking government photo ID.',
    theCustomer: 'the customer who ordered',
    legend: 'ID check',
    idChecked: 'I checked a valid government photo ID',
    matches: 'The name on the ID is {name}',
    ofAge: 'The person is {age} or older',
    privacy: 'Don’t copy or photograph the ID. Northline keeps only that you checked it.',
    handOver: 'Hand over', cantHandOver: 'Can’t hand over', back: 'Back',
    refuseLede: 'The order isn’t handed over. It goes back into your stock; the customer is refunded for the age-restricted items only.',
    why: 'Why', refuseSend: 'Don’t hand over',
    r_no_id: 'No photo ID', r_underage: 'Under age', r_mismatch: 'Name doesn’t match', r_nobody_of_age: 'Nobody of age came', r_intoxicated: 'Appears intoxicated', r_other: 'Another reason',
    error: 'That didn’t go through. Refresh and try again.',
    checkId: 'Check ID · {age}+',
  },
  fr: {
    title: 'Vérifier l’identité · {ref}',
    lede: 'Cette commande contient des articles soumis à un âge minimal. Remettez-la seulement à {name}, {age} ans ou plus, après avoir vérifié une pièce d’identité gouvernementale avec photo.',
    theCustomer: 'la personne qui a commandé',
    legend: 'Vérification d’identité',
    idChecked: 'J’ai vérifié une pièce d’identité gouvernementale valide avec photo',
    matches: 'Le nom sur la pièce est {name}',
    ofAge: 'La personne a {age} ans ou plus',
    privacy: 'Ne copiez ni ne photographiez la pièce. Northline garde seulement le fait que vous l’avez vérifiée.',
    handOver: 'Remettre', cantHandOver: 'Impossible de remettre', back: 'Retour',
    refuseLede: 'La commande n’est pas remise. Elle retourne dans votre stock; le client est remboursé des articles soumis à un âge minimal seulement.',
    why: 'Pourquoi', refuseSend: 'Ne pas remettre',
    r_no_id: 'Aucune pièce avec photo', r_underage: 'N’a pas l’âge requis', r_mismatch: 'Le nom ne correspond pas', r_nobody_of_age: 'Personne ayant l’âge requis', r_intoxicated: 'Semble en état d’ébriété', r_other: 'Une autre raison',
    error: 'L’action n’a pas abouti. Actualisez et réessayez.',
    checkId: 'Vérifier l’identité · {age} ans et plus',
  },
});
