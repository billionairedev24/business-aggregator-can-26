import { defineMessages } from '@northline/ui';

/** Courier progress copy (S-88) — ours; the design shows the courier on the map without words for these states. */
export const useCourierT = defineMessages({
  en: {
    assigned: '{name} will bring your order.',
    assignedNoName: 'A courier will bring your order.',
    onTheWay: '{name} is on the way.',
    onTheWayNoName: 'Your courier is on the way.',
    stops: '{count, plural, =0 {You’re next.} one {# stop before yours.} other {# stops before yours.}}',
    eta: 'At your door about {time}.',
    live: 'Live · updated {time}',
    pin: 'Drop-off PIN {pin}',
    pinHint: 'Give it to the courier if they ask.',
  },
  fr: {
    assigned: '{name} apportera votre commande.',
    assignedNoName: 'Un livreur apportera votre commande.',
    onTheWay: '{name} est en route.',
    onTheWayNoName: 'Votre livreur est en route.',
    stops: '{count, plural, =0 {Vous êtes le prochain.} one {# arrêt avant le vôtre.} other {# arrêts avant le vôtre.}}',
    eta: 'À votre porte vers {time}.',
    live: 'En direct · mis à jour à {time}',
    pin: 'NIP de livraison {pin}',
    pinHint: 'Donnez-le au livreur s’il le demande.',
  },
});
