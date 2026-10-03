---
title: "Scénario de test d’acceptation — Personnel de la console"
---

# Scénario de test d’acceptation — Personnel de la console

| | |
|---|---|
| Profil | Personnel de la console (`staff`) |
| Version | 1.0 |
| Pour qui | Un membre du personnel Northline qui utilise la console pendant le pilote (soutien, confiance et sécurité, répartition, finances). |
| Ce qu’il vous faut | Un ordinateur avec un navigateur à jour; votre connexion de personnel avec une clé d’accès ou une application d’authentification; les rôles de console que vous aurez au lancement. |
| Tiré de | Parcours S-117 1 (file de vérification), 3 (répartition) et 4 (versements); SCREENS.md Console. |

[Version anglaise](../console-staff.md)

## Avant de commencer

1. Utilisez l’environnement pilote fourni par l’équipe, jamais la production.
2. Faites chaque étape dans l’ordre. Cochez **Réussi** si ce que vous voyez correspond au résultat attendu, **Échoué** sinon.
3. Pour chaque échec, et tout ce qui est déroutant, utilisez **Envoyer un commentaire** dans le produit (ou le formulaire, ou votre contact du pilote) et inscrivez le numéro (UAT-…) dans la colonne Notes.
4. N’écrivez ni mot de passe, ni numéro de carte, ni code dans un commentaire; l’écran, la version de l’application, votre langue et votre navigateur ou système de téléphone sont envoyés avec — rien d’autre.
5. À la fin, remplissez le formulaire d’approbation : [formulaire d’approbation](console-staff-signoff.md).

## Étapes

| No | Étape | Résultat attendu | Réussi / Échoué | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Connectez-vous à la console avec votre deuxième facteur. | Vous voyez la vue d’ensemble de votre rôle; la barre latérale n’affiche que vos écrans. | ☐ Réussi ☐ Échoué | |
| 2 | Changez de rôle (si vous en avez plusieurs). | La barre latérale se réduit; le changement figure au journal d’audit. | ☐ Réussi ☐ Échoué | |
| 3 | File de vérification : examinez une entreprise du pilote et approuvez-la. | L’entreprise devient active et est avisée par courriel. | ☐ Réussi ☐ Échoué | |
| 4 | Contrôle des annonces : approuvez une annonce, refusez-en une avec un motif. | Le commerçant voit les deux décisions, le motif dans sa langue. | ☐ Réussi ☐ Échoué | |
| 5 | Opérations de livraison : mettez un livreur en quart et assignez une tournée. | Le livreur reçoit la tournée; la carte l’affiche. | ☐ Réussi ☐ Échoué | |
| 6 | Soutien : répondez à un dossier du pilote avec une réponse type dans la langue du demandeur. | Le demandeur voit la réponse; l’état du dossier change. | ☐ Réussi ☐ Échoué | |
| 7 | Finances : trouvez les versements du pilote et le rapprochement. | Les versements et la vérification Stripe / grand livre concordent. | ☐ Réussi ☐ Échoué | |
| 8 | Tests du pilote : triez un commentaire : trié, accepté (bloquant ou non), responsable, lien du billet. | Chaque étape est enregistrée et figure dans l’historique. | ☐ Réussi ☐ Échoué | |
| 9 | Tests du pilote : fusionnez un doublon; exportez le CSV. | Le doublon pointe vers l’élément; le CSV s’ouvre dans un tableur. | ☐ Réussi ☐ Échoué | |
| 10 | Tests du pilote › Feu vert : lisez le rapport. | Les éléments bloquants ouverts, la couverture par profil et la tendance sur 14 jours sont affichés. | ☐ Réussi ☐ Échoué | |
| 11 | Passez la console en français; refaites un écran. | Tout est en français. | ☐ Réussi ☐ Échoué | |
| 12 | Envoyez une note avec « Envoyer un commentaire ». | Vous recevez un numéro (UAT-…). | ☐ Réussi ☐ Échoué | |
