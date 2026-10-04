---
title: "Scénario de test d’acceptation — Livreur"
---

# Scénario de test d’acceptation — Livreur

| | |
|---|---|
| Profil | Livreur (`courier`) |
| Version | 1.0 |
| Pour qui | Un livreur du pilote qui utilise l’application Northline pour livreurs. |
| Ce qu’il vous faut | Votre téléphone avec l’application pour livreurs (version pilote), localisation activée pendant l’utilisation; une livraison test préparée par l’équipe. |
| Tiré de | Parcours S-117 3 (commande → livraison, côté livreur); SCREENS.md Application livreur. |

[Version anglaise](../courier.md)

## Avant de commencer

1. Utilisez l’environnement pilote fourni par l’équipe, jamais la production.
2. Faites chaque étape dans l’ordre. Cochez **Réussi** si ce que vous voyez correspond au résultat attendu, **Échoué** sinon.
3. Pour chaque échec, et tout ce qui est déroutant, utilisez **Envoyer un commentaire** dans le produit (ou le formulaire, ou votre contact du pilote) et inscrivez le numéro (UAT-…) dans la colonne Notes.
4. N’écrivez ni mot de passe, ni numéro de carte, ni code dans un commentaire; l’écran, la version de l’application, votre langue et votre navigateur ou système de téléphone sont envoyés avec — rien d’autre.
5. À la fin, remplissez le formulaire d’approbation : [formulaire d’approbation](courier-signoff.md).

## Étapes

| No | Étape | Résultat attendu | Réussi / Échoué | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Connectez-vous à l’application pour livreurs. | Vous voyez l’écran de quart avec votre statut. | ☐ Réussi ☐ Échoué | |
| 2 | Commencez votre quart. | Vous apparaissez disponible; la répartition peut vous assigner une tournée. | ☐ Réussi ☐ Échoué | |
| 3 | Ouvrez la tournée qui vous est assignée. | Les arrêts sont dans l’ordre, le prochain est indiqué. | ☐ Réussi ☐ Échoué | |
| 4 | Au commerce, confirmez le ramassage (sac scellé). | L’arrêt indique ramassé; le client est avisé que c’est en route. | ☐ Réussi ☐ Échoué | |
| 5 | Activez le mode avion, arrivez à la remise, puis désactivez-le. | L’application garde ce que vous avez fait et l’envoie au retour du réseau. | ☐ Réussi ☐ Échoué | |
| 6 | Remettez la commande avec le NIP du client (et une photo). | L’arrêt est terminé; le client voit livrée. | ☐ Réussi ☐ Échoué | |
| 7 | Terminez votre quart. | Vous êtes averti si quelque chose n’a pas été envoyé; le quart se termine. | ☐ Réussi ☐ Échoué | |
| 8 | Passez l’application en français et ouvrez un arrêt. | Tout est en français. | ☐ Réussi ☐ Échoué | |
| 9 | Dites à l’équipe une chose qui vous a ralenti : **Commentaires** en haut de n’importe quel écran de l’app des coursiers (ajoutez une capture d’écran si c’est utile; recadrez les renseignements des clients). | Vous voyez le numéro (UAT-…) sur le téléphone; l’élément est dans la file du pilote comme celui d’un coursier. | ☐ Réussi ☐ Échoué | |
