# MCP server for AI agents (S-127)

The api has a built-in [Model Context Protocol](https://modelcontextprotocol.io) server. With it, an AI agent such as
Claude (claude.ai, Claude Desktop, Claude Code), an IDE assistant or the MCP Inspector can do Studio work for a
person. The agent signs in as that person, through northline-auth, and the person approves what the agent may do.

| | |
|---|---|
| endpoint | `https://api.<zone>/mcp` (Streamable HTTP, protocol 2025-11-25; locally `http://localhost:8080/mcp`) |
| sign-in | OAuth 2.1 with northline-auth: authorization code + PKCE (S256), resource indicator, consent screen |
| discovery | `https://api.<zone>/.well-known/oauth-protected-resource/mcp` (RFC 9728). A `401` carries `WWW-Authenticate: Bearer resource_metadata="…", scope="openid merchant mcp"` |
| who | people in a business, with a second factor (`acr=mfa`); partners with `client_credentials` (S-30); Northline staff for the ops tools |
| code | `server/api/src/main/java/ca/northline/mcp` (module `mcp`), `server/auth/src/main/java/ca/northline/auth/mcp` |

Implementation: springdoc-openapi's MCP support (`springdoc-openapi-starter-webmvc-mcp`) turns selected api operations
into tools. Spring AI's MCP server (`spring-ai-starter-mcp-server-webmvc`, Streamable HTTP) serves them. A tool call
is an HTTP request back to the same api with the caller's own token, so every rule that applies in the Studio still
applies: business membership and role (S-30 merchant binding), validation, idempotency and the audit trail.
Decisions are in [DECISIONS.md § S-127](../DECISIONS.md).

## Connecting an agent

### Claude (claude.ai, Claude Desktop)

Settings → Connectors → *Add custom connector* → URL `https://api.<zone>/mcp`. Claude finds the authorization server
on its own, sends you to Northline's sign-in page (passkey or authenticator app required) and then to the consent
screen. Untick `mcp.write` if the agent should only read.

### Claude Code

```sh
claude mcp add --transport http northline https://api.<zone>/mcp
# then, inside Claude Code: /mcp → northline → Authenticate (opens the browser)
```

Locally, with the stack from [local.md](local.md) running:

```sh
claude mcp add --transport http northline-local http://localhost:8080/mcp
```

### MCP Inspector and other clients

`npx @modelcontextprotocol/inspector` → transport *Streamable HTTP*, URL as above. A client that lets you set the
client id uses **`northline-mcp`** (public client, no secret). Its callback must be `http://127.0.0.1:<any
port>/callback` or `/oauth/callback`. A client that doesn't let you set it identifies itself with a Client ID Metadata
Document (see below). Dynamic Client Registration (RFC 7591) is **not** offered.

### Local shortcut without signing in

Under the `local` profile the api accepts `X-Dev-User: <identity.users id>` in place of a token (as for the REST API,
[local.md](local.md)). That dev token has `acr=mfa` and the scopes `merchant mcp mcp.write`. In the Inspector, add the
header under *Authentication → Custom headers*. Other profiles never accept it.

## Client registration

Two kinds of client may get MCP tokens:

1. **`northline-mcp`**, registered in configuration (`northline.oauth.clients.northline-mcp`, [README § OAuth
   clients](README.md#oauth-clients-s-122)). It is a public PKCE client and requires consent and `acr=mfa`. Its
   redirect URIs are loopback IPs on any port (RFC 8252) and Claude's callbacks. Another hosted agent with a fixed
   callback can be added to its `redirect-uris` (every environment) or through an environment-only file
   (`SPRING_CONFIG_ADDITIONAL_LOCATION`).
2. **Client ID Metadata Documents** (MCP 2025-11-25): the agent's `client_id` is an HTTPS URL that serves a JSON
   document (`client_id`, `client_name`, `redirect_uris`, `token_endpoint_auth_method: none`). On first use, auth
   fetches it (no redirects, 5 s, at most 5 KB, public addresses only — never into the cluster). It checks the
   document and registers a public PKCE client with consent. The id is `cimd-<hash>` and the client is stored in
   `auth.oauth2_registered_client`; `client_id` became `varchar(2048)` in V025. The document is fetched again after
   10 min. Such a client gets at most `openid profile merchant mcp mcp.write`, **never `mcp.ops`**. The authorization
   server metadata advertises `client_id_metadata_document_supported: true`.

| variable (auth) | default | |
|---|---|---|
| `MCP_CLIENT_METADATA_DOCUMENTS` | `true` | `false` = only registered clients |
| `MCP_CLIENT_METADATA_HOSTS` | empty | comma-separated document hosts (e.g. `claude.ai`); empty = any public HTTPS host |

`OAuthClientSync` leaves URL clients alone (they are not "stored but not in configuration"). To revoke one, delete its
row: `delete from auth.oauth2_registered_client where client_id = 'https://…'`. Its tokens then expire within the
hour.

## Tokens

- The agent sends `resource=https://api.<zone>/mcp` in the authorization and token requests (RFC 8707). auth accepts
  only `MCP_RESOURCE` and `MCP_DOCS_RESOURCE`; anything else is `invalid_target`. A token request may narrow the
  resource but not widen it.
- The access token's `aud` holds the resource, `northline-api` and the client id. It also carries `client_id`, `acr`,
  `merchants` and `roles`. It lives 1 h. **No refresh token**: public clients refresh only with DPoP (S-29), which MCP
  clients don't send, so after an hour the agent signs in again. The auth session usually makes that silent.
- The api's MCP endpoint accepts only tokens whose `aud` contains `MCP_RESOURCE`; otherwise `401 invalid_token`. Set
  `MCP_RESOURCE` to the same value in api and auth; both default to `${API_PUBLIC_URL}/mcp`.
- **MCP tokens work only on `/mcp`.** On `/api/**` without the MCP server's internal header, a token addressed to the MCP
  server is `403 mcp_token`, so an agent can't call operations outside the tool list. The header is a random secret
  per process that the MCP server adds to its own calls.
- A person's token without a second factor is `403` with `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="mfa"`
  (RFC 9470). The auth server already refuses to issue one to `northline-mcp` and to URL clients
  (`mfa-required-clients`).

## Scopes and tools

| scope | grants |
|---|---|
| `mcp` | the read tools and the resources |
| `mcp.write` | the write tools (each confirmed, below) |
| `mcp.ops` | staff tools; also needs the `staff` role and `acr=mfa` |
| `merchant` | needed with the above for business data (as in the Studio) |

Partners (S-30) use their own scopes: `api.read` gives the read tools and `api.write` the write tools, for the
businesses they are bound to.

`tools/list` shows only the tools the token may use. Tool annotations say `readOnlyHint` / `destructiveHint`.

| area | read (`mcp`) | write (`mcp.write`) |
|---|---|---|
| businesses | `list_my_businesses`, `get_business` | |
| listings | `search_listings`, `get_listing` | `update_listing_price_stock` (new `PATCH …/listings/{id}/price-stock`; products with variants are changed in the Studio) |
| orders | `list_orders`, `get_order`, `get_kitchen_board` | `mark_order_packed`, `kitchen_accept_order`, `kitchen_mark_ready`, `kitchen_hand_off` |
| bookings | `list_appointments`, `get_appointment`, `list_quote_requests` | `appointment_en_route`, `appointment_on_site` |
| availability | `get_availability_hours`, `get_booking_rules`, `list_time_off`, `get_time_off_conflicts` | `set_availability_hours`, `add_time_off` |
| messages | `list_message_threads`, `get_message_thread` | `reply_to_thread` |
| money | `get_earnings`, `get_payouts_overview`, `list_payouts` | — |
| reviews | `get_review_summary`, `list_reviews` | — |
| staff (`mcp.ops`) | `list_registry_reviews` | `decide_registry_review` |

**Never available to agents:** refunds, payouts, checkout and payments, team and role changes, security settings,
deleting anything. The list is `AgentTools.ALL`; an api operation that isn't in it is not a tool. A new tool is a
code change, reviewed like any other.

Resources: `northline://me` (businesses and roles), `northline://merchants/{merchantId}` and
`northline://guides/agent-quickstart` (how to use the tools, `server/api/src/main/resources/mcp/agent-quickstart.md`).

## Confirmations

Every write tool works in two steps. The first call changes nothing; it answers `confirmation_required` with what the
call would change. The agent shows that to the person. The same call with **exactly the same arguments** within
5 min (`northline.mcp.confirm-window`) then runs it. The same change repeated within 10 min
(`repeat-window`) is answered `already_done` and never applied twice, so a client that retries can't double it.
Confirmations live in `MCP_STORE` (Valkey keys `nl:mcp:*` in the cloud), so they work across replicas.

## Limits

Per person (token subject), across replicas with `MCP_STORE=redis`:

| variable (api) | default | |
|---|---|---|
| `MCP_CALLS_PER_MINUTE` | 60 | tool calls |
| `MCP_WRITES_PER_MINUTE` | 10 | write tool calls (confirmation steps included) |
| `MCP_STORE` | `redis` (cloud profiles), `memory` (local) | `memory` is refused under staging/prod |

Over a limit, the MCP endpoint answers `429` with `Retry-After`. The operations' own limits also apply.

## Audit

Every tool call writes a row to `developer.audit_log`. It shows in the business's audit log, or with no business for
staff tools.

| action | when |
|---|---|
| `mcp.tool_call` | a read, or a confirmed write (`confirmed: true`) |
| `mcp.tool_confirmation` | the first step of a write |
| `mcp.tool_refused` | missing scope, or a repeated change (`reason`) |

Each row holds the tool, its kind and the client id. The operation it runs writes its own audit rows too. Request
bodies are not stored.

## Environments

| | local | dev | staging | prod |
|---|---|---|---|---|
| route | `http://localhost:8080/mcp` | `api.dev.<zone>/mcp` | `api.staging.<zone>/mcp` | `api.<zone>/mcp` |
| `MCP_STORE` | memory | redis | redis | redis |
| Client ID Metadata Documents | on | on | on | on (consider `MCP_CLIENT_METADATA_HOSTS`) |
| `X-Dev-User` | yes | no | no | no |

Helm: `apps.api.mcp: true` (default) routes `/mcp` and `/.well-known/oauth-protected-resource` on the api host
(`templates/ingress.yaml`; Gateway API and Ingress). `false` removes both, and agents can't connect. No new secret.
The agent header secret is generated per process. Argo CD needs no change.

Under prod, the OpenAPI document is still generated (the tools come from it), but `/v3/api-docs` and Swagger UI are
closed (S-125's `northline.docs.enabled: false`).

## Operations

- **Turn agents off quickly:** set `apps.api.mcp: false` (removes the route), or remove `mcp`/`mcp.write` from
  `northline-mcp`'s scopes and set `MCP_CLIENT_METADATA_DOCUMENTS=false`. Tokens already issued expire within 1 h.
- **One misbehaving agent:** find its client id in the audit rows (`details->>'client'`). Delete the client row (URL
  clients), or revoke the person's sessions (Studio → Security), which ends the auth session that silent sign-ins use.
- **"Tools list is empty"**: the token lacks `mcp` (check the consent), or the api started without its OpenAPI model
  (`springdoc.pre-loading-enabled` must stay `true`).
- **`invalid_target`**: the agent's resource differs from `MCP_RESOURCE`. Check the scheme, the host and that there is
  no trailing slash.
- **`401 invalid_token` with a fresh token:** `MCP_RESOURCE` differs between api and auth.
- **Long tool calls:** responses are plain JSON (no long-lived streams). The edge's default timeouts are enough.

## What has not run against the real services

The OAuth flow and the MCP session are tested end to end with the MCP Java SDK client against the api, with tokens
minted in the test and auth's flow in MockMvc. They have **not** been tried with Claude (claude.ai, Desktop or Code)
or the MCP Inspector against a deployed environment. Claude's callback URLs and its Client ID Metadata Document
behaviour are taken from the MCP specification, not observed.
