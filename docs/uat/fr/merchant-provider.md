---
title: "Scénario de test d’acceptation — Commerçant — prestataire de services"
---

# Scénario de test d’acceptation — Commerçant — prestataire de services

| | |
|---|---|
| Profil | Commerçant — prestataire de services (`provider`) |
| Version | 1.0 |
| Pour qui | Le propriétaire (ou un technicien) d’une entreprise de services qui participe au pilote. |
| Ce qu’il vous faut | Un ordinateur ou une tablette avec un navigateur à jour; votre connexion Northline avec une clé d’accès ou une application d’authentification; un téléphone pour le côté client (un client du pilote ou un deuxième compte fourni par l’équipe du pilote). |
| Tiré de | Parcours S-117 1 (intégration), 2 (soumission → réservation → séquestre) et 4 (versement); SCREENS.md Studios (prestataire) et Intégration. |

[Version anglaise](../merchant-provider.md)

## Avant de commencer

1. Utilisez l’environnement pilote fourni par l’équipe, jamais la production.
2. Faites chaque étape dans l’ordre. Cochez **Réussi** si ce que vous voyez correspond au résultat attendu, **Échoué** sinon.
3. Pour chaque échec, et tout ce qui est déroutant, utilisez **Envoyer un commentaire** dans le produit (ou le formulaire, ou votre contact du pilote) et inscrivez le numéro (UAT-…) dans la colonne Notes.
4. N’écrivez ni mot de passe, ni numéro de carte, ni code dans un commentaire; l’écran, la version de l’application, votre langue et votre navigateur ou système de téléphone sont envoyés avec — rien d’autre.
5. À la fin, remplissez le formulaire d’approbation : [formulaire d’approbation](merchant-provider-signoff.md).

## Étapes

| No | Étape | Résultat attendu | Réussi / Échoué | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Connectez-vous au Studio avec votre deuxième facteur. | Vous arrivez au tableau de bord de votre entreprise. Le bouton « Envoyer un commentaire » est dans le coin inférieur. | ☐ Réussi ☐ Échoué | |
| 2 | Ouvrez Conformité et vérifiez chaque élément de vérification. | Chaque vérification affiche son état; tout ce qui est dû indique quoi envoyer et quand. | ☐ Réussi ☐ Échoué | |
| 3 | Ouvrez la page de votre entreprise (Page) et affichez l’aperçu. | L’aperçu correspond à ce que voient les clients : services, zone desservie, avis. | ☐ Réussi ☐ Échoué | |
| 4 | Ajoutez un service à prix fixe, puis un service « sur soumission ». | Les deux sont enregistrés; le premier affiche son prix, l’autre « Soumission ». | ☐ Réussi ☐ Échoué | |
| 5 | Réglez vos disponibilités de la semaine et bloquez une heure. | Le calendrier affiche les heures ouvertes et l’heure bloquée. | ☐ Réussi ☐ Échoué | |
| 6 | Depuis le téléphone client, demandez une soumission pour votre service « sur soumission ». | La demande apparaît dans le Studio (Rendez-vous / Messages) avec la description du travail. | ☐ Réussi ☐ Échoué | |
| 7 | Rédigez une soumission détaillée : main-d’œuvre, une pièce, un dépôt, la validité; envoyez-la. | Le client voit chaque ligne, le dépôt et la date d’expiration. | ☐ Réussi ☐ Échoué | |
| 8 | Le client accepte et paie le dépôt. | La réservation figure dans Rendez-vous; l’argent est retenu en séquestre, pas encore à vous. | ☐ Réussi ☐ Échoué | |
| 9 | Le jour venu, enregistrez votre arrivée et marquez le travail terminé. | Le client est invité à approuver le travail. | ☐ Réussi ☐ Échoué | |
| 10 | Le client approuve. | Le séquestre est libéré (à l’approbation ou 48 heures après la fin du travail); il apparaît dans Revenus. | ☐ Réussi ☐ Échoué | |
| 11 | Ouvrez Versements. | Le prochain versement et sa date sont affichés; un versement payé indique « En route vers votre banque ». | ☐ Réussi ☐ Échoué | |
| 12 | Passez le Studio en français et refaites un écran de votre choix. | Tout l’écran est en français, y compris les messages d’erreur. | ☐ Réussi ☐ Échoué | |
| 13 | Envoyez une note avec « Envoyer un commentaire », avec une capture d’écran. | Vous recevez un numéro (UAT-…). L’équipe du pilote peut le retrouver. | ☐ Réussi ☐ Échoué | |
