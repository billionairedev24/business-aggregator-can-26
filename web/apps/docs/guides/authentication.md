---
sidebar_position: 2
title: Authentication
---

# Authentication

Every token comes from Northline's authorization server (OAuth 2.1 and OpenID Connect). For the endpoints, see the
[OAuth 2.1 / OpenID Connect reference](/api/auth-public/); discovery is at `/.well-known/openid-configuration`.

## Partners: client credentials with `private_key_jwt`

Your integration is registered as the client `partner:<name>`, with your public keys (a JWK Set URL, or registered
EC P-256 / RSA ≥ 2048 keys) and the businesses it may act for. There is no client secret. You prove your identity
with a JWT you sign:

```http
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=partner:acme
&scope=api.read
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
&client_assertion=<JWT>
```

The assertion must meet all of these:

- It is signed with ES256, RS256 or PS256 by a key registered for you.
- `iss` and `sub` are your client id.
- `aud` is the issuer or the token endpoint.
- `exp` and `iat` are present, and the assertion is valid for at most 5 minutes.
- `jti` is unique: each assertion can be used once.

The token lasts 15 minutes. It carries `api.read` and/or `api.write`, and opens only the businesses bound to your
client:

- a business outside that list answers `403 not_bound`;
- an endpoint that isn't open to partners answers `403 partner_not_allowed`.

You may request at most 60 tokens per hour. Above that the server answers `429` with `Retry-After`. Reuse each token
until it expires.

## Apps: authorization code with PKCE, and DPoP on mobile

First-party apps sign users in with the authorization code flow and PKCE (`S256` only).

- **Browser apps** never hold tokens: their backend-for-frontend does.
- **Mobile apps** bind their tokens to a key with DPoP (RFC 9449). They send
  `Authorization: DPoP <token>` together with a `DPoP` proof header. A DPoP-bound token sent as a Bearer token is
  refused.
- **Refresh tokens** rotate on every use.
