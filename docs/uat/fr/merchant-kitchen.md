---
title: "Scénario de test d’acceptation — Commerçant — cuisine"
---

# Scénario de test d’acceptation — Commerçant — cuisine

| | |
|---|---|
| Profil | Commerçant — cuisine (`kitchen`) |
| Version | 1.0 |
| Pour qui | Le propriétaire ou un cuisinier d’un restaurant ou d’une cuisine qui participe au pilote. |
| Ce qu’il vous faut | La tablette que vous utiliserez en cuisine, son activé; votre connexion Northline; un téléphone pour le côté client. |
| Tiré de | Parcours S-117 3 (commande, version restauration) et le test rapide du Studio; SCREENS.md Studios (cuisine). |

[Version anglaise](../merchant-kitchen.md)

## Avant de commencer

1. Utilisez l’environnement pilote fourni par l’équipe, jamais la production.
2. Faites chaque étape dans l’ordre. Cochez **Réussi** si ce que vous voyez correspond au résultat attendu, **Échoué** sinon.
3. Pour chaque échec, et tout ce qui est déroutant, utilisez **Envoyer un commentaire** dans le produit (ou le formulaire, ou votre contact du pilote) et inscrivez le numéro (UAT-…) dans la colonne Notes.
4. N’écrivez ni mot de passe, ni numéro de carte, ni code dans un commentaire; l’écran, la version de l’application, votre langue et votre navigateur ou système de téléphone sont envoyés avec — rien d’autre.
5. À la fin, remplissez le formulaire d’approbation : [formulaire d’approbation](merchant-kitchen-signoff.md).

## Étapes

| No | Étape | Résultat attendu | Réussi / Échoué | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Connectez-vous au Studio sur la tablette de cuisine. | Vous arrivez au tableau des commandes (KDS); « Envoyer un commentaire » est dans le coin. | ☐ Réussi ☐ Échoué | |
| 2 | Ouvrez Menu : ajoutez un plat avec ses allergènes et un groupe d’options. | Le plat est enregistré masqué jusqu’à l’approbation; ses allergènes s’affichent. | ☐ Réussi ☐ Échoué | |
| 3 | Réglez les heures de la cuisine de la semaine, avec un jour fermé. | Le site client indique la cuisine fermée ce jour-là. | ☐ Réussi ☐ Échoué | |
| 4 | Depuis le téléphone client, commandez deux plats à emporter. | La commande sonne sur le tableau en quelques secondes et n’est annoncée qu’une fois. | ☐ Réussi ☐ Échoué | |
| 5 | Laissez la tablette en veille 5 minutes, puis passez une autre commande. | La nouvelle commande sonne quand même à son arrivée. | ☐ Réussi ☐ Échoué | |
| 6 | Acceptez, marquez prête, puis remettez la première commande. | Chaque étape fait avancer le billet; le client voit « Prête » puis « Ramassée ». | ☐ Réussi ☐ Échoué | |
| 7 | Mettez les nouvelles commandes en pause, puis reprenez. | Pendant la pause, le site client ne prend pas de commandes pour votre cuisine. | ☐ Réussi ☐ Échoué | |
| 8 | Marquez un plat comme épuisé. | Le site client l’affiche épuisé; on ne peut pas le commander. | ☐ Réussi ☐ Échoué | |
| 9 | Ouvrez Revenus après la remise. | L’argent de la commande est libéré à la remise et apparaît dans Revenus. | ☐ Réussi ☐ Échoué | |
| 10 | Passez en français et traitez une commande au tableau. | Le tableau, les boutons et les annonces sont en français. | ☐ Réussi ☐ Échoué | |
| 11 | Envoyez une note avec « Envoyer un commentaire ». | Vous recevez un numéro (UAT-…). | ☐ Réussi ☐ Échoué | |
