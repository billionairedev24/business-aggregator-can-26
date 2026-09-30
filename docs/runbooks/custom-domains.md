# Custom domains — storefronts on merchants' own domains (S-31)

How a merchant's own domain (`book.prairiewrench.ca`) gets verified, receives a certificate and serves their
storefront; how the edge scales past Gateway API's 64 listeners; how the consumer app finds the page for a `Host`; and
what to do when a domain is stuck. Code: `ca.northline.merchants` (`domain.DomainClaim`, `application.CustomDomainService`,
`integration.GatewayDomainEdge`), chart `edge.domainReconciler`; decisions in `docs/DECISIONS.md` § S-31.

> **Status (2026-09-30):** the reconciler was rehearsed on kind against a real API server, cert-manager 1.18 and Envoy
> Gateway 1.5 as the chart's ServiceAccount (§ 9: 70 domains on two merged shards, served over TLS, removed cleanly).
> DNS verification is tested against stand-ins only (in-memory zone, WireMock speaking DNS over HTTPS, a UDP server for
> JNDI) — never against a public resolver — and Let's Encrypt itself was not involved (a local CA stood in). The
> consumer app that renders the page by `Host` does not exist yet (E-3/E-7), only the api lookup it will call.

Other runbooks: [edge](edge.md) (the Gateway, cert-manager, external-dns) · [gitops](gitops.md) · [notifications](notifications.md)

## 1. The shape

```
merchant's DNS:  book.prairiewrench.ca  CNAME  pages.northline.ca            (apex: ALIAS/ANAME, or A → edge address)
                 _northline-verify.book.prairiewrench.ca  TXT  nl-<32 base32 chars>

api  ── every minute: due DNS checks (DoH / JNDI) ─────────────────────▶ merchants.storefronts.custom_domain_*
     ── every minute: reconcile the edge (one replica, advisory lock) ─▶ Kubernetes API, namespace northline-<env> only
            Gateway northline-custom-0 … N   one HTTPS listener per domain (≤ 64 per Gateway)
            Certificate nl-cd-<hash>          cert-manager, Issuer northline-acme-custom, HTTP-01
            HTTPRoute  nl-cd-<hash>           → Service northline-consumer, HSTS (no includeSubDomains)
Envoy Gateway (mergeGateways) ── all Gateways of class envoy share one proxy fleet and ONE load balancer
                                  = the address pages.<zone> resolves to
browser ─ https://book.prairiewrench.ca ─▶ LB ─▶ Envoy (SNI → the domain's listener) ─▶ consumer app
consumer app ─ GET /api/v1/public/storefronts/by-host?host=book.prairiewrench.ca ─▶ api  (§ 6)
```

### Why an in-cluster reconciler with shard Gateways

| option | verdict |
|---|---|
| (a) per-domain listeners on the environment's Gateway, reconciled by the api | **chosen, sharded** — the only thing the chart adds is a Role in the app's own namespace. The 64-listener limit is lifted by putting merchants' listeners on shard Gateways `northline-custom-N`; Envoy Gateway's `mergeGateways` serves every Gateway of the class from one proxy fleet behind one load balancer, so the CNAME target never changes. |
| (b) on-demand TLS at the edge (Caddy `on_demand_tls`, Cloudflare for SaaS, CloudFront SaaS Manager, Front Door) | not chosen: Envoy/cert-manager have no on-demand issuance; the CDN products are cloud- or vendor-specific. Stays possible in front (the by-host endpoint doubles as the "ask" check such products need). |
| (c) GitOps: the api opens a PR to `edge.customDomains` | not chosen: a merchant would wait for a human review and a sync (staging/prod sync by hand, S-15), and the api would need write access to the repository. `edge.customDomains` stays for exceptions pinned by hand. |

`ListenerSet` (Gateway API's answer to the 64-listener limit) is still experimental and not in Envoy Gateway 1.5;
when it is stable the shards can become ListenerSets attached to the one Gateway without changing anything else.
Works the same on EKS, GKE, AKS and kind: the only cloud part remains the load balancer of the Envoy Service (S-17).

## 2. Lifecycle

| state | means | leaves when |
|---|---|---|
| `pending` | waiting for the TXT and CNAME/A records | both found → `verified`; 7 days without them → `expired` (owners emailed) |
| `verified` | ownership and routing proven; not on the edge yet — page unpublished, business not active, or waiting for a certificate slot (`rate_limited`, `capacity`) | the reconciler requests the certificate → `issuing` |
| `issuing` | listener, Certificate and route written; cert-manager is solving HTTP-01 | certificate Ready + listener Programmed → `live` (owners emailed); cert-manager gives up → `failed` (owners emailed) |
| `live` | serving over TLS | page unpublished / business paused → `verified` (listener removed); certificate lost → `issuing` |
| `failed` | Let's Encrypt refused; the listener and Certificate are removed (no retries against Let's Encrypt meanwhile) | re-checked every 6 h (3 failures in a row: only on the merchant's "Check now") → `verified` (tried again) or `pending` |
| `expired` | not verified within 7 days; no more scheduled checks | "Check now" → `verified`, or `pending` with a new 7-day window |

Checks: pending every 5 min in the first hour, every 30 min that day, then every 2 h; proven domains every 6 h.
**Grace period:** when a `verified`/`issuing`/`live` domain's records stop pointing at us (definitive answers only —
a resolver error changes nothing), it keeps serving for 72 h (`DOMAINS_GRACE_PERIOD`), is checked every 30 min, and
the owners get an email with the deadline; records back in time = forgiven. After the deadline it returns to
`pending` (email), the reconciler removes it from the edge, and the page stays reachable at `northline.ca/<slug>`.

Every change is published as `custom_domain.changed` (topic `merchants.storefront`, key = storefront id, schema
`merchants.custom_domain_changed.v1`): the consumer app evicts its host cache on it; the api emails the owners when
`notice` is set (template `custom-domain`, en/fr, a service notice that ignores the notification matrix).

### What counts as "pointing at us"

- **CNAME:** the CNAME chain of the name (up to 8 hops) reaches `DOMAINS_TARGET_HOST` (`pages.<zone>`).
- **Addresses (apex):** every A/AAAA address the name resolves to is one of `pages.<zone>`'s addresses or of
  `DOMAINS_EDGE_ADDRESSES`. ALIAS/ANAME records and CNAME flattening resolve to our addresses and pass; one stray
  address (an old host left in place) fails, because half the visitors and the HTTP-01 challenge would go there.
- **A proxy in front** (Cloudflare "orange cloud", another CDN) hides our addresses: `not_pointing`. The merchant must
  set the record to DNS-only. (Serving behind a merchant's own CDN is not supported.)
- **Ownership:** a TXT value at `_northline-verify.<domain>` equal to the claim's token (other TXT values there are
  ignored). The TXT record must stay: it is re-checked every 6 h and on every claim conflict.
- **Apex detection** is by name: two labels (`example.ca`), or three under a known second-level suffix (`on.ca`, `qc.ca`,
  `gc.ca`, `co.uk`, …). The Studio then shows ALIAS guidance (or A records when `DOMAINS_EDGE_ADDRESSES` is set).

## 3. Serving and TLS

- **Shard Gateways** `northline-custom-N` (label `northline.ca/custom-domain=true`): one HTTPS listener per domain
  (`cd-<hash>`, hostname = the domain, Secret `nl-cd-<hash>-tls`), at most `listenersPerGateway` (64). A domain keeps
  its shard while it serves; freed slots are reused; empty shards are deleted. HTTP (port 80: the redirect and the
  ACME challenges) stays on the environment's Gateway, whose `http` listener has no hostname.
- **Certificates** `nl-cd-<hash>`: ECDSA P-256, new key on renewal, renewed 30 days early, from Issuer
  `northline-acme-custom` over **HTTP-01** (merchants' zones aren't ours, DNS-01 is impossible). cert-manager owns the
  TLS Secret (`enableCertificateOwnerRef`), so deleting the Certificate deletes the key; the api has no Secret access.
- **Issuer per environment:** Let's Encrypt **production in prod only**; dev and staging use the **staging** endpoint
  (browsers show a certificate warning there — on purpose; the chart refuses production outside prod).
- **Routes** `nl-cd-<hash>` → `northline-consumer`, with the edge's headers except that HSTS has no
  `includeSubDomains`/`preload`: a merchant's other subdomains (mail, their own site) are theirs. The same now holds
  for the hand-pinned `edge.customDomains`. `external-dns.alpha.kubernetes.io/controller: none`: their DNS is theirs.
- **TLS policy:** ClientTrafficPolicy `northline-custom` targets the shards by label (TLS 1.2+, h2, trusted proxy
  hops), like the environment's Gateway.
- **Idempotent:** objects carry a hash of their spec (`northline.ca/spec-hash`) and are applied (server-side apply,
  field manager `northline-domains`) only when it changes; a quiet reconcile is three list calls.
- **Argo CD** ignores the reconciler's objects as orphans (`northline.runtimeResources` in the app of apps) and never
  prunes them (they carry no tracking annotation).

### Let's Encrypt limits and how they are respected

| Let's Encrypt limit | protection |
|---|---|
| 300 new orders per account per 3 h | `DOMAINS_ISSUE_PER_HOUR` (20) new certificates per hour across all merchants; the rest wait as `rate_limited` |
| 5 failed validations per hostname per account per hour | a certificate is requested only after DNS proved the CNAME/A points at us; on failure the Certificate is removed (cert-manager's own retries stop) and tried again after 6 h, at most 3 times |
| 50 certificates per registered domain per week; 5 duplicates per week | one request per page per `DOMAINS_REQUEST_COOLDOWN` (1 h); only active businesses with a published page get certificates |
| staging vs production | separate ACME accounts (`northline-acme-custom-account-key`) per environment; production only in prod |

## 4. Abuse controls

- **One page per domain.** `unique(custom_domain)` (V016). When another page asks for a domain that is taken, the
  holder is **re-verified** in its own transaction: a holder that never proved it (pending, expired, failed) loses it
  unless its TXT record is there — its owners are emailed (`released`); a holder whose domain is proven keeps it, but
  its records are checked right away, so if the DNS owner removed its TXT record the grace period starts (email) and
  the domain frees up when it ends. The claimant gets "That domain is already connected to another page." until then.
- **Our own zones are blocked:** `northline.ca` and anything under it ("Enter a domain like book.yourbusiness.ca."),
  the environment's zone, and `DOMAINS_BLOCKED_SUFFIXES` ("That domain can't be connected to a Northline page.").
- **Proof of control:** 160-bit random token per claim (a new one for every new domain); re-checked every 6 h.
- **No certificate without a reason to serve:** approved (active) business, published page, proven domain.
- **Capacity:** `DOMAINS_MAX` (1000) domains on the edge; beyond it new ones wait as `capacity` — raise it knowingly
  (each domain is a listener, a Certificate, a Secret and a route; Envoy handles thousands of SNI filter chains).
- **Rate limits in the Studio:** "Check now" asks DNS at most every 15 s per domain (`DOMAINS_CHECK_COOLDOWN`).
- **Least privilege:** the api's Role covers `gateways`, `httproutes` (gateway.networking.k8s.io) and `certificates`
  (cert-manager.io) in its namespace; its ServiceAccount token is mounted only when the reconciler is on. Accepted
  risk: the api could rewrite the environment's own Gateway (RBAC can't restrict create/list by name); Argo CD shows
  that as drift and dev self-heals. A separate reconciler Deployment would remove it (not built).

## 5. Configuration

| variable | default | notes |
|---|---|---|
| `DOMAINS_DNS_PROVIDER` | `local` | `doh` (DNS over HTTPS, RFC 8484 wire format, POST) · `jndi` (the JDK's DNS client, UDP/TCP 53) · `local` (in-memory zone; refused in staging/prod). Chart: `edge.domainReconciler.dns.provider` (`doh`) |
| `DOMAINS_DOH_URL` | `https://private.canadianshield.cira.ca/dns-query` | CIRA Canadian Shield "Private" (Canadian, no filtering). Any RFC 8484 server works (Cloudflare `https://cloudflare-dns.com/dns-query`, Google `https://dns.google/dns-query`, Quad9) |
| `DOMAINS_DNS_SERVERS` | empty | jndi only: `host[:port]` list; empty = the pod's resolver (in-cluster DNS may cache NXDOMAIN longer) |
| `DOMAINS_EDGE_PROVIDER` | `local` | `kubernetes` (the reconciler) · `local` (in memory, ready at once; refused in staging/prod). Chart: set with `edge.domainReconciler.enabled` |
| `DOMAINS_TARGET_HOST` | `pages.northline.ca` | the chart sets it from `urls.pages` |
| `DOMAINS_EDGE_ADDRESSES` | empty | reserved public IPs of the load balancer, for apex A records. Chart: `edge.domainReconciler.edgeAddresses` |
| `DOMAINS_BLOCKED_SUFFIXES` | `northline.ca` | the chart adds the environment's zone and `edge.domainReconciler.blockedSuffixes` |
| `DOMAINS_ISSUE_PER_HOUR`, `DOMAINS_REQUEST_COOLDOWN`, `DOMAINS_MAX` | `20`, `PT1H`, `1000` | § 3, § 4 |
| `DOMAINS_VERIFY_WINDOW`, `DOMAINS_GRACE_PERIOD`, `DOMAINS_CHECK_COOLDOWN` | `P7D`, `PT72H`, `PT15S` | § 2 |
| `DOMAINS_CHECK_INTERVAL`, `DOMAINS_EDGE_INTERVAL` | `PT1M`, `PT1M` | scheduler |
| `DOMAINS_EDGE_NAMESPACE`, `DOMAINS_EDGE_GATEWAY_CLASS`, `DOMAINS_EDGE_GATEWAY_PREFIX`, `DOMAINS_EDGE_LISTENERS_PER_GATEWAY`, `DOMAINS_EDGE_ISSUER`, `DOMAINS_EDGE_SERVICE`, `DOMAINS_EDGE_SERVICE_PORT`, `DOMAINS_EDGE_RESPONSE_HEADERS` | chart | set by the chart from the release namespace, `edge.*` and `apps.consumer`; don't set by hand |

Staging and prod require `DOMAINS_DNS_PROVIDER`, `DOMAINS_EDGE_PROVIDER` and `DOMAINS_TARGET_HOST` at start-up
(the chart provides all three). No secret is involved: the api authenticates to Kubernetes with its projected
ServiceAccount token, DoH needs no account.

## 6. Routing contract (consumer app)

`pages.<zone>` and every live merchant domain route to the consumer app. For a request whose `Host` is not one of
Northline's own hosts, the consumer app asks the api which page to render:

```
GET /api/v1/public/storefronts/by-host?host=<Host header>          (no authentication; GET only)
200  Cache-Control: max-age=60, public
     the same body as GET /api/v1/storefronts/{slug}: { slug, url, pageKind, brandColor, logoUrl, tagline, ctaLabel,
     announcement, customDomain, publishedAt, sections: [{kind, settings}], business: {…} }
404  anything else: not a live custom domain, the page unpublished, the business not active, a malformed host
```

- `host` may carry a port and any case (`Book.Example.CA:443`); international names in Unicode or punycode.
- Only `live` domains resolve (including during the grace period). `verified`/`issuing` don't: the edge doesn't
  route them yet anyway.
- Cache per host for at most 60 s, and drop an entry when `custom_domain.changed` arrives for it (Kafka topic
  `merchants.storefront`, field `domain`) — e.g. a domain moved to another page or disconnected.
- On 404 the consumer app answers 404 (never another merchant's page, never a redirect to `northline.ca`).
- Links on the page may stay relative; the canonical URL is `https://<customDomain>/` when `customDomain` is set.

## 7. Set up per environment

1. S-17 in place (Envoy Gateway, cert-manager, the environment's Gateway serving `pages.<zone>`).
2. Merge this story's add-on changes and **sync the add-ons first** (`envoy-gateway-<env>`, `cert-manager-<env>`):
   `mergeGateways: true` moves the proxies to a Service named after the GatewayClass — **the load balancer is replaced
   and its address changes once**; external-dns moves the records by itself, and `DOMAINS_EDGE_ADDRESSES` (if you
   reserved an IP) must be the new address. Do it before any merchant points an apex A record at it.
3. Sync `northline-<env>`: Issuer `northline-acme-custom` Ready, Role/RoleBinding, the api restarts with its token
   mounted and logs `Custom domains: Gateway API edge in namespace northline-<env>`.
4. Optional (apex A records): reserve a static IP for the load balancer (Elastic IP on the NLB, a regional static IP
   on Google Cloud, a static public IP on Azure — annotations in `addons/envoy-gateway/manifests/<cloud>`) and set
   `edge.domainReconciler.edgeAddresses`.
5. Rehearse with a domain you own: Studio › Business page › Custom domain, add the two records, "Check now", publish;
   `kubectl -n northline-<env> get gateways,certificates,httproutes -l northline.ca/custom-domain=true`.

## 8. Operations

| task | how |
|---|---|
| where is a domain? | `select custom_domain, custom_domain_status, custom_domain_problem, custom_domain_checked_at, custom_domain_next_check_at, custom_domain_dns_lost_at, custom_domain_failures from merchants.storefronts where custom_domain = 'book.example.ca';` |
| what DNS says | `dig +short CNAME book.example.ca`, `dig +short book.example.ca`, `dig +short TXT _northline-verify.book.example.ca` (from outside the cluster; our resolver may still cache an old answer up to its TTL) |
| its edge objects | `kubectl -n northline-<env> get certificate nl-cd-<hash>` — the hash is in the `northline.ca/host` annotation: `kubectl get certificates -l northline.ca/custom-domain=true -o custom-columns=NAME:.metadata.name,HOST:.metadata.annotations.northline\.ca/host,READY:.status.conditions[0].status` |
| stuck `issuing` | `kubectl describe certificate nl-cd-…`, then `kubectl get orders,challenges`: the HTTP-01 challenge must be reachable at `http://<domain>/.well-known/acme-challenge/…` (a proxy/CDN in front, a stray A record, port 80 blocked) |
| re-check now | the merchant's "Check now", or `update merchants.storefronts set custom_domain_next_check_at = now() where custom_domain = '…';` (next minute) |
| take a domain down (abuse) | clear it: `update merchants.storefronts set custom_domain = null, custom_domain_status = null, custom_domain_token = null, custom_domain_status_at = null where custom_domain = '…';` — the next reconcile removes listener, Certificate, Secret and route. Add its zone to `blockedSuffixes` to keep it off |
| shards | `kubectl get gateways -l northline.ca/custom-domain=true -o custom-columns=NAME:.metadata.name,LISTENERS:.spec.listeners[*].name` — ≤ 64 each |
| capacity | domains waiting as `capacity`: raise `DOMAINS_MAX` after checking the Envoy fleet's memory |
| certificate expiry | cert-manager's `certmanager_certificate_expiration_timestamp_seconds{name=~"nl-cd-.*"}` (alerting with S-111) |

## 9. Local and tests

- **Local:** `DOMAINS_DNS_PROVIDER=local` and `DOMAINS_EDGE_PROVIDER=local` (the defaults): enter a domain in the Studio,
  then **Simulate DNS records →** (dev builds; `POST /api/v1/dev/merchants/{id}/storefront/domain/dns`, `local`
  profile only) writes its CNAME/A and TXT into the in-memory zone, checks them and reconciles the local edge — a
  published page's domain goes `live` at once, and `GET /api/v1/public/storefronts/by-host?host=…` answers.
  To try real DNS from a laptop: `DOMAINS_DNS_PROVIDER=doh`.
- **Tests:** `DomainClaimTest` (state machine), `DnsInspectorTest` (what counts as pointing), `DnsResolversTest`
  (DoH against WireMock speaking the wire format, JNDI against a UDP server), `GatewayDomainEdgeTest` (the reconciler
  against an in-memory namespace: shards past 64, removal, reuse, rationing, capacity, status, idempotence),
  `HttpKubeApiWireMockTest` (REST paths, server-side apply, token rotation), `CustomDomainApiTest` (the api end to
  end with the jobs, grace period, claim conflicts, emails, by-host, 403s), `DevOnboardingTest` (simulation),
  Studio `CustomDomainField.test.tsx` (en + fr-CA); `deploy/helm/validate.sh` (RBAC, issuer per environment,
  refusals) and `deploy/argocd/validate.sh` (every rendered kind whitelisted).
- **kind:** `deploy/kind/custom-domains.sh` (after `deploy/argocd/kind/rehearse.sh up edge`, or any kind cluster with
  cert-manager and Envoy Gateway): applies the add-on's GatewayClass/EnvoyProxy (`mergeGateways`), a local CA, the chart's
  `edge.yaml` with `values-local-kind-edge.yaml` (reconciler on) and a stand-in consumer, prints what the api's
  ServiceAccount may do, runs the reconciler (`CustomDomainsKindRehearsal`, skipped unless `NL_KIND_API` is set) with a
  token of that ServiceAccount, curls each domain through the Envoy Service, then removes everything
  (`HOSTS`, `LISTENERS`, `KEEP=1`).

  **Rehearsed 2026-09-30** (kind 0.30 / Kubernetes 1.34 with a runc wrapper node image — this Docker lacks
  `CAP_SYS_RESOURCE` —, cert-manager 1.18.2 from the Bitnami builds because quay.io is blocked here, Envoy Gateway 1.5.1
  and Envoy 1.35.3 from Docker Hub, a local CA instead of Let's Encrypt):
  - RBAC: the ServiceAccount may create/patch/delete Gateways, HTTPRoutes and Certificates in its namespace; it may not
    read Secrets, create Roles, or write in another namespace (`kubectl auth can-i`).
  - 5 domains on shards of 2 listeners → 3 Gateways, `PROGRAMMED True`, one address; certificates Ready and listeners
    Programmed after 6 s; each domain answered 200 over HTTP/2 through the **one** merged Envoy Service with
    `strict-transport-security: max-age=63072000` (no includeSubDomains); ClientTrafficPolicy `northline-custom`
    Accepted on all three shards (label selector); port 80 answered 301; an unknown SNI and TLS 1.1 were refused.
  - 70 domains on shards of 64 → `northline-custom-0` (64 listeners, accepted by the Gateway CRD's validation) and `-1`
    (6); all Ready after 61 s; still one Envoy Service; the first five and the last domain answered 200.
  - Removal: every Gateway, Certificate and HTTPRoute deleted, and cert-manager deleted the 70 TLS Secrets with their
    Certificates (owner references) — the api never touched a Secret.
  - Not rehearsable locally: Let's Encrypt (HTTP-01 from the internet), a public resolver, a cloud load balancer; the
    api ran on the host with the ServiceAccount's token, not in a pod (same code path: token and CA files).

## 10. Troubleshooting

| symptom | cause / fix |
|---|---|
| stays `pending` with `txt_missing` although the record exists | the record is at the wrong name (many DNS hosts append the zone: enter `_northline-verify.book` not the full name), or negative caching at the resolver (up to the zone's SOA minimum, often 1 h) |
| `txt_mismatch` | the TXT holds an older token (the domain was re-entered: a new token each time) |
| `not_pointing` with a correct CNAME | a proxy in front (Cloudflare orange cloud) or an extra A/AAAA record |
| `dns_error` for a while | the DoH endpoint unreachable (egress 443 blocked? `DOMAINS_DOH_URL`), or the merchant's name servers failing |
| `rate_limited` for hours | more than `DOMAINS_ISSUE_PER_HOUR` domains became ready at once; they drain at that pace |
| `failed` after publishing | HTTP-01 couldn't reach us: see "stuck issuing" in § 8; after fixing, "Check now" |
| the api logs `No adapter …` / 403 from the API server | the chart's Role/RoleBinding missing (Argo CD project whitelist) or the token not mounted (`edge.domainReconciler.enabled`) |
| certificate warning in dev/staging | expected: Let's Encrypt staging |
