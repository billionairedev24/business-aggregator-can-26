# Edge runbook — DNS, TLS, the Gateway and WAF options (S-17)

How requests reach Northline on any cloud: the public hosts, where their DNS lives, how certificates are issued and
renewed, what the edge enforces (HTTPS only, TLS 1.2+, HSTS), the WAF options per cloud, and storefront custom domains.

> **Status (2026-09-30):** defined and checked offline (`deploy/helm/validate.sh`, `deploy/argocd/validate.sh`,
> `infra/terraform/scripts/validate.sh`), and rehearsed on kind with a local CA (below). No public zone, cloud load
> balancer or Let's Encrypt certificate exists yet: Terraform is unapplied and `northline.ca` is not delegated.

Other runbooks: [gitops](gitops.md) · [deploy](deploy.md) · [infrastructure](infrastructure.md) · [prod](prod.md) ·
[dev](dev.md) · [staging](staging.md) · [email](email.md) (SPF/DKIM/DMARC records)

## The shape

```
browser ──▶ [optional WAF/CDN: Cloudflare · CloudFront+AWS WAF · Cloud Armor · Front Door]
        ──▶ cloud layer-4 load balancer (NLB · GCP passthrough NLB · Azure LB), created for the Envoy Service
        ──▶ Envoy proxies (Envoy Gateway, namespace envoy-gateway-system)      TLS ends here: cert-manager Secrets
        ──▶ Gateway northline (namespace northline-<env>): :80 → 301 https, :443 one listener per host
        ──▶ HTTPRoutes (+ HSTS and other response headers) ──▶ Services studio, bff, auth, api, consumer
DNS: external-dns writes A/ALIAS records for every HTTPRoute hostname into the environment's zone
Certificates: cert-manager, Let's Encrypt (HTTP-01 through the Gateway; DNS-01 through the cloud's DNS as an option)
```

| piece | where | notes |
|---|---|---|
| Gateway, HTTPRoutes, Issuer, Certificates, ClientTrafficPolicy | chart `deploy/helm/northline` (`templates/edge.yaml`, `templates/ingress.yaml`), values `edge.*`, `gateway.*` | on in `values-dev/staging/prod.yaml`; no cloud logic |
| Envoy Gateway v1.5.1 (+ Gateway API CRDs), GatewayClass `envoy`, EnvoyProxy per cloud | Argo CD add-on `envoy-gateway-<env>`: `deploy/argocd/addons/envoy-gateway/` | the per-cloud part is the load balancer Service (`manifests/<cloud>/envoyproxy.yaml`) |
| cert-manager v1.18.2 | add-on `cert-manager-<env>`: `deploy/argocd/addons/cert-manager/` | Gateway API support on; `--issuer-ambient-credentials` for DNS-01 with its workload identity |
| external-dns (chart 1.18.0) | add-on `external-dns-<env>`: `deploy/argocd/addons/external-dns/` + `envs/<env>/addons/external-dns.yaml` | provider, zone filter and identity from Terraform |
| DNS zone, identities, record permissions | Terraform `modules/dns/*` (`record_writers`), stacks (identities `external-dns`, `cert-manager`) | env-root outputs `gitops_addon_values`, `helm_values.edge` |

### Why Gateway API with Envoy Gateway

- **ingress-nginx is retired** (Kubernetes SIG Network, March 2026: no releases or security fixes since). The chart's
  default is Gateway API; the `Ingress` template stays for clusters whose controller only speaks Ingress (AWS Load
  Balancer Controller, GKE Ingress, AKS application routing, Traefik): `ingress.enabled: true`, `gateway.enabled:
  false`, `ingress.className` — the TLS entries and the HTTP-01 solver then use the Ingress (checked in
  `validate.sh`).
- **Envoy Gateway** runs the same on EKS, GKE, AKS and kind, from Docker Hub images, with the Gateway API features we
  need (per-host TLS listeners, redirects, header filters, TLS version policy, client IP detection). The cloud only
  provides a layer-4 load balancer for the Envoy Service, so TLS, certificates and headers are identical everywhere.
- Cloud-managed gateways stay possible through `edge.gateway.className` (e.g. `gke-l7-global-external-managed` with
  Cloud Armor, Azure Application Gateway for Containers). The Envoy-specific `ClientTrafficPolicy` is then not
  rendered, and TLS policy moves to that gateway's own settings.

## Hosts

| environment | zone (Terraform `dns_zone_name`) | hosts |
|---|---|---|
| prod | `northline.ca` | `northline.ca` (consumer), `studio.northline.ca`, `auth.northline.ca`, `api.northline.ca`, `pages.northline.ca`, `console.northline.ca` (placeholder) |
| staging | `staging.northline.ca` | `staging.northline.ca`, `studio.`, `auth.`, `api.`, `pages.`, `console.` + `staging.northline.ca` |
| dev | `dev.northline.ca` | `dev.northline.ca`, `studio.`, `auth.`, `api.`, `pages.`, `console.` + `dev.northline.ca` |

- **Studio: `studio.`** (the backlog's name; earlier drafts said `business.`, nothing was ever deployed there).
  The passkey RP id stays the zone (`urls.webauthnRpId`), shared by the Studio and the consumer origins.
- **api:** `/api/v1` (S-29, `apps.api.tokenClients: true`, the default): the mobile apps call it directly with
  DPoP-bound tokens ([mobile-auth.md](mobile-auth.md)); every endpoint still needs a token except the Stripe webhooks
  (S-12, signed), the calendar change notifications (S-32, `/api/v1/webhooks/calendar/…`, verified per channel secret —
  [calendar-sync.md](calendar-sync.md)), the Shopify / Square / Lightspeed webhooks and OAuth redirect URIs (S-35,
  `/api/v1/webhooks/commerce/…` HMAC-verified, `/api/v1/commerce/oauth/…` single-use state —
  [commerce-sync.md](commerce-sync.md)) and the email unsubscribe link (S-13). Anything outside `/api/v1` (actuator, OpenAPI) answers 404 at
  the Gateway. Browsers reach the api through the BFF on the Studio host — including the calendar OAuth redirect URIs
  (`https://studio.<zone>/api/v1/calendar/oauth/…`), which need the member's session. `tokenClients: false` narrows the route back
  to `/api/v1/webhooks/stripe` (+ `/connect`), `/api/v1/webhooks/calendar`, `/api/v1/webhooks/commerce`,
  `/api/v1/commerce/oauth` and `/api/v1/email/unsubscribe`.
- **console:** placeholder. No route, certificate or record exists until `apps.console.enabled` (E-8).
- **pages:** storefronts, served by the consumer app by `Host` (storefront rendering is E-3/E-7 work), plus merchant
  domains (below).
- Hosts come from `urls.*`; changing one in `values-<env>.yaml` moves the route, the listener, the certificate and the
  DNS record together.

## DNS delegation

Terraform creates one public zone per environment (`module.dns`: Route 53, Cloud DNS with DNSSEC, Azure DNS) and
outputs its name servers (`terraform output dns`). Delegate once:

1. **`northline.ca` (prod):** at the registrar, replace the name servers with the four from
   `terraform -chdir=infra/terraform/envs/<cloud>/prod output -json dns | jq -r '.name_servers[]'`. On Google Cloud
   also add the DS record (DNSSEC) at the registrar: `gcloud dns dns-keys list --zone northline-prod`.
2. **`dev.` and `staging.`:** in the prod zone, add NS records for the subdomain pointing at that environment's
   name servers. The prod zone may live in another account or cloud, so do this by hand, once:
   ```sh
   # AWS (prod zone in Route 53)
   aws route53 change-resource-record-sets --hosted-zone-id <prod zone id> --change-batch '{"Changes":[{"Action":"UPSERT",
     "ResourceRecordSet":{"Name":"staging.northline.ca","Type":"NS","TTL":3600,"ResourceRecords":[{"Value":"ns-1.awsdns-…"},…]}}]}'
   # Google Cloud
   gcloud dns record-sets create staging.northline.ca. --zone northline-prod --type NS --ttl 3600 --rrdatas ns-cloud-a1.googledomains.com.,…
   # Azure
   az network dns record-set ns add-record -g <prod rg> -z northline.ca -n staging -d <ns1>   # once per name server
   ```
3. Check: `dig +short NS staging.northline.ca` returns the environment's servers; `dig +trace studio.staging.northline.ca`.
4. Add a CAA record in each zone so only Let's Encrypt may issue: `northline.ca. CAA 0 issue "letsencrypt.org"` (and
   `0 iodef "mailto:platform@northline.ca"`). Mail records (SPF, DKIM, DMARC) stay manual: [email.md](email.md).

**external-dns** then manages the host records: it watches the HTTPRoutes of the cluster, writes an A record (ALIAS
to the NLB on Route 53) for each hostname in its zone, and marks each record it owns with a TXT record
(`_extdns.<host>`, owner `northline-<env>`). It never touches records it didn't create: the apex NS/SOA, CAA, MX,
SPF/DKIM stay manual. `policy: sync` removes its records when a route goes away. Merchant domains are excluded
(outside `domainFilters`, and their routes carry `external-dns.alpha.kubernetes.io/controller: none`).

## Certificates

- **Issuer `northline-acme`** (namespaced, in `northline-<env>`), Let's Encrypt production, account key in Secret
  `northline-acme-account-key`, expiry mail to `platform@northline.ca` (`edge.certManager.issuer.email`).
- **One Certificate per host** (`northline-tls-<host>`), ECDSA P-256, a new key on every renewal, renewed 30 days
  before the 90-day expiry. **HTTP-01** by default: cert-manager adds a temporary HTTPRoute for
  `/.well-known/acme-challenge/<token>` on the Gateway's port-80 listener (exact path beats the redirect route), so
  nothing but the Gateway and public DNS is needed, on any cloud.
- **DNS-01 (option):** `edge.certManager.issuer.solver: dns01`. The solver block comes from Terraform
  (`helm_values.edge.certManager.issuer.dns01`: `route53` / `cloudDNS` / `azureDNS`) and cert-manager authenticates
  as its workload identity (Terraform grants it record changes on the zone only). Needed for **`edge.certManager.
  wildcard: true`**: one `*.<zone>` + `<zone>` certificate instead of one per host (fewer certificates, nothing
  published per host in Certificate Transparency logs beyond the wildcard). Merchant domains always use HTTP-01.
- **Rate limits (Let's Encrypt):** 50 certificates per registered domain per week, 5 duplicate certificates per
  week. Six hosts × three environments is far below that; for experiments use the staging server
  (`edge.certManager.issuer.server: https://acme-staging-v02.api.letsencrypt.org/directory`) in dev.
- **Check:** `kubectl -n northline-<env> get certificates` (all `READY True`), `kubectl describe certificate <name>`,
  then `kubectl get orders,challenges` for a stuck one. Expiry: cert-manager's metric
  `certmanager_certificate_expiration_timestamp_seconds` (alerting comes with S-111).
- Staging and prod refuse a non-ACME issuer (`ca`/`selfSigned` exist for kind only).

## What the edge enforces

| | how | value |
|---|---|---|
| HTTPS only | port 80 has one route: `301` to `https://<host>/…` (except ACME challenges) | — |
| TLS 1.2+ | Envoy Gateway `ClientTrafficPolicy` on the Gateway, `edge.tls.minVersion` | `1.2`; Envoy's default cipher list (ECDHE + AES-GCM / ChaCha20); ALPN h2, http/1.1 |
| HSTS | `ResponseHeaderModifier` on every route rule | `max-age=63072000; includeSubDomains` (`edge.hsts`); `preload` only once every subdomain of `northline.ca` is HTTPS for good (submission at hstspreload.org is hard to undo) |
| other headers | same filter, `edge.responseHeaders` | `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`. CSP and frame options stay with each app (the Studio's nginx sets its own) |
| unknown hosts | no listener matches the SNI | the TLS handshake fails (no default certificate, nothing served by IP) |
| api surface | the api host routes two path prefixes only | the rest 404 at the Gateway |
| client address | `edge.trustedProxyHops` (0 = clients hit the LB directly; 1 behind Cloudflare/CloudFront/Front Door) | `X-Forwarded-For` trusted that many hops, so rate limits (S-9) see the real client |

### Trusted proxies (S-20)

auth and the bff believe `X-Forwarded-For/-Proto/-Host` (client IP for the rate limits and the sessions list, the
scheme for redirect URIs) and auth the city header (`CLIENT_CITY_HEADER`) only from private addresses
(`TRUSTED_PROXIES`, Tomcat's internal proxies for the bff). Envoy's proxy pods have pod addresses, so the chart's
NetworkPolicy is what keeps everyone else out: staging and prod allow the public apps' ports only from the
`envoy-gateway-system` namespace (`networkPolicy.ingressFrom` in `values-staging.yaml` / `values-prod.yaml`; dev and
kind keep "any namespace"). If you run Envoy Gateway's proxies in another namespace (Gateway-namespace mode) or use
another edge (GKE Gateway for Cloud Armor: traffic comes from Google's front ends `35.191.0.0/16`, `130.211.0.0/22`),
replace that entry with the right `namespaceSelector` or `ipBlock`s. Set `CLIENT_CITY_HEADER` only when a CDN in
front **overwrites** that header on every request (CloudFront does for `CloudFront-Viewer-City`); Envoy alone passes a
client's own header through, so the city would be the client's choice (display-only, but misleading).

## WAF and DDoS protection

A WAF is an option in front, not a dependency: the chart and Gateway work the same with or without one. Pick one per
environment (prod at least); in every case turn on the provider's **managed OWASP / common-exploit rule set in
blocking mode**, a **rate-based rule on `auth.<zone>`** (sign-in, passkey, OTP paths), and set
`edge.trustedProxyHops: 1`.

| option | how it fits | notes |
|---|---|---|
| **Cloudflare** (any cloud) | proxied DNS records ("orange cloud") in front of the load balancer; SSL mode **Full (strict)** (our Let's Encrypt certificates are valid origin certificates); WAF managed rules + rate limiting | cloud-neutral; DNS then lives at Cloudflare (use external-dns's `cloudflare` provider with an API token instead of the cloud zone), or keep the cloud zone and CNAME the hosts to Cloudflare. HTTP-01 keeps working through the proxy. Restrict the LB to Cloudflare's IP ranges (`loadBalancerSourceRanges` in the EnvoyProxy Service) |
| **AWS**: CloudFront + AWS WAF | CloudFront distribution per environment with the NLB as origin (HTTPS, origin host = the public host), AWS WAF web ACL (AWSManagedRulesCommonRuleSet, KnownBadInputs, SQLi, rate-based rule) attached to CloudFront; Shield Standard included | AWS WAF can't attach to an NLB. Alternative: AWS Load Balancer Controller + ALB with `alb.ingress.kubernetes.io/wafv2-acl-arn` and the chart's Ingress mode (TLS on the ALB with ACM then) |
| **Google Cloud**: Cloud Armor | needs an external Application Load Balancer: GKE Gateway (`edge.gateway.className: gke-l7-global-external-managed`) + `GCPBackendPolicy` with a Cloud Armor security policy (preconfigured OWASP rules `sqli-v33-stable`, `xss-v33-stable`, …, rate limiting) | the passthrough NLB in front of Envoy can't carry Cloud Armor (only Adaptive Protection / network-level). Or Cloudflare |
| **Azure**: Front Door Premium + WAF | Front Door profile with the AKS load balancer as origin (Private Link optional), WAF policy with the Microsoft Default Rule Set + Bot Manager + rate limit | also brings the CDN; restrict the LB to the `AzureFrontDoor.Backend` service tag |

The WAF itself (accounts, policies, DNS cut-over) is not in Terraform yet; each option is a manual step in
[prod.md § Readiness checklist](prod.md#readiness-checklist).

## Storefront custom domains (`pages.`)

A merchant who owns `book.example.ca` points it at us with a **CNAME to `pages.northline.ca`** (Studio › Storefront
shows this). S-31 verifies the CNAME and records `verified_at`. S-17 serves a verified domain:

1. Add it to the environment's chart values by PR (GitOps; the S-31 automation can open the PR):
   ```yaml
   # deploy/argocd/envs/prod/values.yaml
   edge:
     customDomains:
       - host: book.example.ca
   ```
2. After the sync: a Gateway listener for the host, a Certificate over **HTTP-01** (the CNAME already sends the
   challenge to us), and an HTTPRoute to the consumer app, with the same TLS policy and HSTS. external-dns leaves the
   merchant's DNS alone.
3. `kubectl -n northline-prod get certificate northline-tls-book-example-ca` → Ready in a minute or two.

Limits and alternatives:

- **Apex domains** (`example.ca` without `www`) can't be CNAMEs. The merchant needs ALIAS/ANAME support at their DNS
  host, or A records to the load balancer's address (fixed only with a reserved IP: an Elastic IP on the NLB, a
  reserved regional IP on Google Cloud, a static public IP on Azure — not set up yet).
- **Scale:** a Gateway holds at most 64 listeners (Gateway API limit), so this handles the pilot's handful of domains.
  Beyond that: several Gateways, `ListenerSet` once Gateway API and Envoy Gateway ship it as stable, or on-demand TLS
  at a CDN (Cloudflare for SaaS custom hostnames, CloudFront SaaS Manager, Front Door custom domains), which issues
  certificates per merchant without cluster changes. Decide before the storefront launch (S-31).
- Removing a domain from the list removes its listener, route and certificate (the Secret stays until deleted).

## Setting it up (per environment)

1. **Terraform** applied ([infrastructure.md](infrastructure.md)): the zone, the `external-dns` and `cert-manager`
   identities and their record permissions on the zone.
2. **Delegate the zone** ([§ DNS delegation](#dns-delegation)), add the CAA record.
3. **Identities and zone settings into Git** (PR):
   ```sh
   cd infra/terraform/envs/<cloud>/<env>
   for a in external-dns cert-manager external-secrets; do
     terraform output -json gitops_addon_values | jq ".[\"$a\"]" | yq -P > ../../../../../deploy/argocd/envs/<env>/addons/$a.yaml
   done
   terraform output -json helm_values | yq -P > ../../../../../deploy/argocd/envs/<env>/infra.yaml   # incl. edge.certManager.issuer.dns01
   ```
4. **Sync** (dev by itself; staging/prod by a deployer): `envoy-gateway-<env>` and `cert-manager-<env>`, then
   `external-dns-<env>`, then `northline-<env>` ([gitops.md](gitops.md)).
5. **Verify:** `kubectl -n northline-<env> get gateway northline` (PROGRAMMED True, an address);
   `kubectl -n northline-<env> get certificates` (Ready); `dig +short studio.<zone>` = the Gateway address;
   `curl -sI https://studio.<zone>` (200, `strict-transport-security`); `curl -sI http://studio.<zone>` (301);
   `openssl s_client -connect studio.<zone>:443 -tls1_1` fails.

Without Argo CD, install the three add-ons with Helm using the same values files and apply
`deploy/argocd/addons/envoy-gateway/manifests/{common,<cloud>}` ([deploy.md](deploy.md)).

## Local rehearsal (kind)

On the GitOps rehearsal cluster ([gitops.md § Local rehearsal](gitops.md#local-rehearsal-kind)):

```sh
deploy/argocd/kind/rehearse.sh edge     # cert-manager + Envoy Gateway (release manifests), GatewayClass/EnvoyProxy
                                        # (manifests/common + kind), a local CA, envs/local + values-local-kind-edge.yaml
SVC=$(kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=northline -o name)
kubectl -n envoy-gateway-system port-forward $SVC 28443:443 28080:80 &
curl --cacert ~/.cache/northline-gitops-rehearsal/ca.crt --resolve auth.kind.northline.test:28443:127.0.0.1 \
  https://auth.kind.northline.test:28443/.well-known/openid-configuration -D -
```

Let's Encrypt can't reach a laptop, so the chart's Issuer is `type: ca` with a local root (`values-local-kind-edge.yaml`);
everything else (Gateway, listeners, redirect, headers, TLS policy, Certificates) is what the clouds get.
`CERT_MANAGER_IMAGES` / `ENVOY_IMAGES` replace unreachable registries (`upstream=copy` pairs).

**Rehearsed 2026-09-30** (kind 1.34, Argo CD 3.1.1, cert-manager 1.18.2 from the Bitnami builds because quay.io is
blocked on the rehearsal machine, Envoy Gateway 1.5.1 and Envoy 1.35.3 from Docker Hub):

- Argo CD synced the edge through the `northline-local` project (Gateway, ClientTrafficPolicy, Issuer, Certificates
  whitelisted): Issuer Ready, the api and auth Certificates Ready within seconds, Gateway `PROGRAMMED True`.
- `https://auth.kind.northline.test` served the OIDC discovery document over HTTP/2 with a certificate from the
  local CA (SAN = the host) and `strict-transport-security: max-age=63072000; includeSubDomains`,
  `x-content-type-options: nosniff`, `referrer-policy: strict-origin-when-cross-origin`.
- TLS 1.2 and 1.3 accepted; a TLS 1.1 handshake refused by the server (`alert protocol version`).
- Plain HTTP answered `301` to `https://auth.kind.northline.test/x`.
- On the api host `/actuator/health` answered 404 at the Gateway, `/api/v1/email/unsubscribe` reached the api.
- A TLS handshake for an unknown host (SNI `evil.example`) was reset: no default certificate.
- Not rehearsable locally: Let's Encrypt (HTTP-01/DNS-01), external-dns against a real zone, cloud load balancers, WAFs.

## Troubleshooting

| symptom | cause / fix |
|---|---|
| Certificate stays `False`, Challenge `pending` (HTTP-01) | DNS for the host doesn't point at the Gateway yet (external-dns log, `dig`), or port 80 isn't reachable from the internet (LB, WAF blocking `/.well-known/acme-challenge/`) |
| Challenge fails with 403/404 behind Cloudflare | a WAF rule or "Always Use HTTPS" at the CDN; allow `/.well-known/acme-challenge/*` over HTTP, or switch to DNS-01 |
| DNS-01 `AccessDenied` / `403` | cert-manager's ServiceAccount lacks the identity annotation (`envs/<env>/addons/cert-manager.yaml`), or cert-manager runs without `--issuer-ambient-credentials` |
| no DNS record appears | external-dns log (`kubectl -n external-dns logs deploy/external-dns`): zone filter, identity, `txtOwnerId` clash with another cluster |
| browser shows the wrong certificate / handshake reset | the host has no listener (not in `urls.*` / `edge.customDomains`) or its Secret doesn't exist yet |
| client IPs are the CDN's in the logs / rate limits | set `edge.trustedProxyHops: 1` when a CDN/WAF is in front |
| Gateway not `PROGRAMMED`, no address | the Envoy Service's load balancer isn't provisioned: cloud quota, subnet tags (AWS `kubernetes.io/role/elb`), `kubectl -n envoy-gateway-system describe svc` |
