---
sidebar_position: 3
title: Webhooks
---

# Webhooks

Une entreprise abonne un point de terminaison HTTPS à des événements dans Studio › Paramètres › API. Northline
envoie ensuite chaque événement à ce point en `POST`, en JSON. Chaque type d’événement et le schéma de son contenu
figurent dans la [référence des webhooks](/api/api-webhooks/). Les contenus ne portent que des identifiants et des
montants, jamais les coordonnées des clients.

## Vérifier la signature

Chaque envoi porte un en-tête `Northline-Signature` de cette forme :

```
Northline-Signature: t=1790790312,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
```

Pour la vérifier :

1. Prenez `t` dans l’en-tête, puis calculez `HMAC-SHA256(secret, "<t>.<corps brut de la requête>")`. Le secret est
   la valeur `whsec_…` complète du point de terminaison.
2. Comparez le résultat à chaque valeur `v1` en temps constant. Tant qu’un secret remplacé reste valide, il y a deux
   valeurs `v1`.
3. Refusez l’envoi si `t` date de plus de 5 minutes.

Autres en-têtes : `Northline-Event-Id` (stable d’une tentative à l’autre; dédupliquez sur lui),
`Northline-Event-Type`, `Northline-Delivery-Id` et `Northline-Delivery-Attempt`.

## Livraison

- **Accusé de réception** : toute réponse `2xx` accuse réception. Tout le reste est réessayé, y compris les
  redirections, les délais dépassés et les erreurs TLS.
- **Nouvelles tentatives** : l’attente commence à 30 secondes et triple chaque fois, jusqu’à 12 heures au plus.
  Après 13 tentatives (environ 3 jours), l’envoi est marqué en échec.
- **Désactivation** : un point de terminaison sans succès depuis 3 jours, avec au moins 10 échecs de suite, est
  désactivé, et les propriétaires de l’entreprise en sont avisés par courriel.
