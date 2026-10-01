{{/*
Resource name of an app: northline-<app>. Fixed on purpose — the ServiceAccount names are bound to cloud identities by
Terraform (northline-api, northline-auth, northline-bff, northline-worker).
*/}}
{{- define "northline.appName" -}}
northline-{{ . }}
{{- end }}

{{- define "northline.chart" -}}
{{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Common labels. Call with (dict "root" $ "app" "<name>"). */}}
{{- define "northline.labels" -}}
helm.sh/chart: {{ include "northline.chart" .root }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: northline
app.kubernetes.io/version: {{ include "northline.imageTag" (dict "root" .root "app" (dict)) | quote }}
northline.ca/environment: {{ .root.Values.global.environment | quote }}
{{ include "northline.selectorLabels" . }}
{{- end }}

{{- define "northline.selectorLabels" -}}
app.kubernetes.io/name: {{ include "northline.appName" .app }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .app }}
{{- end }}

{{/* Image tag: app override, else global.image.tag, else Chart.appVersion. (dict "root" $ "app" $appValues) */}}
{{- define "northline.imageTag" -}}
{{- $image := default (dict) .app.image -}}
{{- default (default .root.Chart.AppVersion .root.Values.global.image.tag) $image.tag -}}
{{- end }}

{{/* Full image reference: <registry>/<repository>:<tag>[@<digest>]. (dict "root" $ "app" $appValues) */}}
{{- define "northline.image" -}}
{{- $registry := trimSuffix "/" .root.Values.global.image.registry -}}
{{- $ref := printf "%s:%s" .app.image.repository (include "northline.imageTag" .) -}}
{{- if $registry }}{{ $ref = printf "%s/%s" $registry $ref }}{{ end -}}
{{- if .app.image.digest }}{{ $ref = printf "%s@%s" $ref .app.image.digest }}
{{- else if .root.Values.global.image.requireDigest }}{{ fail (printf "global.image.requireDigest: apps.%s.image.digest is empty — promote the images first (deploy/argocd/promote.sh, docs/runbooks/gitops.md)" .app.image.repository) }}
{{- end -}}
{{- $ref -}}
{{- end }}

{{- define "northline.springProfiles" -}}
{{- default .Values.global.environment .Values.global.springProfiles -}}
{{- end }}

{{/* Host part of a URL: https://auth.dev.northline.ca → auth.dev.northline.ca */}}
{{- define "northline.host" -}}
{{- $u := urlParse . -}}
{{- (split ":" $u.host)._0 -}}
{{- end }}

{{/* Name of the ConfigMap with Terraform's config_env. */}}
{{- define "northline.infraConfigMap" -}}
{{- default "northline-infra" .Values.existingInfraConfigMap -}}
{{- end }}

{{/*
Name of the Secret an app's secret variables come from. (dict "root" $ "name" "<app>")
With External Secrets (S-6) each app has its own Secret, written by its ExternalSecret; otherwise the shared one.
*/}}
{{- define "northline.secretName" -}}
{{- if .root.Values.externalSecrets.enabled -}}
northline-{{ .name }}-secrets
{{- else -}}
{{- .root.Values.secrets.existingSecret -}}
{{- end -}}
{{- end }}

{{/*
External Secrets: the remote key (name in the secrets manager) of a variable. externalSecrets.remoteKeys (Terraform's
secret_env) wins; otherwise remoteKeyPrefix ("{env}" = global.environment) + secretNames.<VAR>.
(dict "root" $ "key" "<VAR>") → the remote key, or "" when unknown.
*/}}
{{- define "northline.remoteKey" -}}
{{- $es := .root.Values.externalSecrets -}}
{{- $explicit := get (default (dict) $es.remoteKeys) .key -}}
{{- if $explicit -}}
{{- $explicit -}}
{{- else -}}
{{- $name := get (default (dict) $es.secretNames) .key -}}
{{- if $name -}}
{{- printf "%s%s" (replace "{env}" .root.Values.global.environment (default "" $es.remoteKeyPrefix)) $name -}}
{{- end -}}
{{- end -}}
{{- end }}

{{/*
Variables an app's ExternalSecret maps: every required one, and the optional ones listed in
externalSecrets.optionalKeys (they must hold a value in the secrets manager: an ExternalSecret fails as a whole when
one remote secret is empty or missing). (dict "root" $ "app" $appValues) → YAML list.
*/}}
{{- define "northline.externalKeys" -}}
{{- $keys := list -}}
{{- range $key, $required := (default (dict) .app.secretEnv) -}}
{{- if or $required (has $key $.root.Values.externalSecrets.optionalKeys) -}}
{{- $keys = append $keys $key -}}
{{- end -}}
{{- end -}}
{{- toYaml $keys -}}
{{- end }}

{{/*
Environment (non-secret) of one app, as a dict. Spring apps: derived URLs + env + apps.<app>.env; static/node apps:
their own variables. (dict "root" $ "name" "<app>" "app" $appValues)
*/}}
{{- define "northline.appEnv" -}}
{{- $root := .root -}}
{{- $v := $root.Values -}}
{{- $env := dict -}}
{{- if eq .app.type "spring" -}}
{{- $_ := set $env "SPRING_PROFILES_ACTIVE" (join "," (concat (list (include "northline.springProfiles" $root)) (default (list) .app.profiles))) -}}
{{- $_ := set $env "AUTH_ISSUER" $v.urls.auth -}}
{{- $_ := set $env "AUTH_INTERNAL_URL" (printf "http://%s:%d" (include "northline.appName" "auth") (int $v.apps.auth.port)) -}}
{{- $_ := set $env "API_URL" (printf "http://%s:%d" (include "northline.appName" "api") (int $v.apps.api.port)) -}}
{{- $_ := set $env "STUDIO_ORIGIN" $v.urls.studio -}}
{{- $_ := set $env "CONSUMER_ORIGIN" $v.urls.consumer -}}
{{- $_ := set $env "CONSOLE_ORIGIN" $v.urls.console -}}
{{- $_ := set $env "WEBAUTHN_RP_ID" $v.urls.webauthnRpId -}}
{{- $_ := set $env "API_PUBLIC_URL" $v.urls.api -}}
{{- $_ := set $env "SERVER_PORT" (toString .app.port) -}}
{{- /* S-111: OTLP to the environment's Collector; one sampling ratio for every app (ConsistentSampling). */ -}}
{{- $_ := set $env "OTEL_TRACES_SAMPLER_ARG" (toString $v.observability.tracesSampleRatio) -}}
{{- $_ := set $env "OTEL_RESOURCE_ATTRIBUTES" (printf "deployment.environment.name=%s,service.version=%s" $v.global.environment (include "northline.imageTag" (dict "root" $root "app" .app))) -}}
{{- if $v.observability.collector.enabled -}}
{{- $_ := set $env "OTEL_EXPORT_ENABLED" "true" -}}
{{- $_ := set $env "OTEL_EXPORTER_OTLP_ENDPOINT" (printf "http://%s:4318" (include "northline.appName" "otel-collector")) -}}
{{- end -}}
{{- if and (eq .name "auth") $v.partners -}}
{{- /* S-30: northline.oauth.partners from the ConfigMap northline-auth-partners (templates/auth-partners.yaml). */ -}}
{{- $_ := set $env "SPRING_CONFIG_ADDITIONAL_LOCATION" "optional:file:/config/partners/" -}}
{{- end -}}
{{- if eq .name "api" -}}
{{- /* S-31: custom domains — the DNS target, blocklist and, with the reconciler, the edge it writes. */ -}}
{{- $env = merge $env (include "northline.domainsEnv" $root | fromYaml) -}}
{{- end -}}
{{- if and (eq .name "api") $v.migrations.enabled -}}
{{- /* S-16: the migration Job owns Flyway; the api only runs against the migrated schema. */ -}}
{{- $_ := set $env "SPRING_FLYWAY_ENABLED" "false" -}}
{{- end -}}
{{- $env = merge (deepCopy (default (dict) .app.env)) (deepCopy (default (dict) $v.env)) $env -}}
{{- else if eq .app.type "static" -}}
{{- $_ := set $env "NL_AUTH_ORIGIN" $v.urls.auth -}}
{{- if or (eq .name "docs") (eq .name "docs-internal") -}}
{{- /* S-126: links to the services' own viewers where they serve them (S-125: dev and staging, never prod), and the
       origins Scalar's "Try it" may call. */ -}}
{{- $swagger := list -}}
{{- if and $v.apps.api.docsRoutes (ne $v.global.environment "prod") -}}
{{- $swagger = list (printf "api|%s/docs" $v.urls.api) (printf "auth|%s/docs" $v.urls.auth) (printf "studio-bff|%s/bff/docs" $v.urls.studio) -}}
{{- end -}}
{{- $_ := set $env "NL_DOCS_SWAGGER" (join "," $swagger) -}}
{{- $_ := set $env "NL_DOCS_CONNECT" (join " " (list $v.urls.api $v.urls.auth)) -}}
{{- end -}}
{{- $env = merge (deepCopy (default (dict) .app.env)) $env -}}
{{- else -}}
{{- $_ := set $env "PORT" (toString .app.port) -}}
{{- $cbff := get $v.apps "consumer-bff" -}}
{{- if and (eq .name "consumer") $cbff $cbff.enabled -}}
{{- /* S-45: server-side rendering fetches public data through the consumer-bff, in-cluster. */ -}}
{{- $_ := set $env "NL_BFF_URL" (printf "http://%s:%d" (include "northline.appName" "consumer-bff") (int $cbff.port)) -}}
{{- end -}}
{{- if eq .name "consumer" -}}
{{- /* S-45: sign-out (and S-62's sign-in pages) call northline-auth from the browser. */ -}}
{{- $_ := set $env "NL_AUTH_ORIGIN" $v.urls.auth -}}
{{- /* S-54: business pages on pages.<zone> and merchants' own domains link back to the site (server/page-hosts.mjs). */ -}}
{{- $_ := set $env "NL_SITE_ORIGIN" $v.urls.consumer -}}
{{- with $v.urls.pages }}{{- $_ := set $env "NL_PAGES_HOST" (urlParse .).host -}}{{- end -}}
{{- /* S-61: "Sell or offer a service" enters the Studio's onboarding. */ -}}
{{- $_ := set $env "NL_STUDIO_ORIGIN" $v.urls.studio -}}
{{- end -}}
{{- $env = merge (deepCopy (default (dict) .app.env)) $env -}}
{{- end -}}
{{- toYaml $env -}}
{{- end }}

{{/* Pod labels from Terraform's workload identity output (e.g. azure.workload.identity/use). */}}
{{- define "northline.identityPodLabels" -}}
{{- $wi := get (default (dict) .root.Values.workloadIdentities) .name -}}
{{- if $wi }}{{- with $wi.pod_labels }}{{ toYaml . }}{{ end }}{{- end -}}
{{- end }}

{{- define "northline.identityAnnotations" -}}
{{- $wi := get (default (dict) .root.Values.workloadIdentities) .name -}}
{{- if $wi }}{{- with $wi.service_account_annotations }}{{ toYaml . }}{{ end }}{{- end -}}
{{- end }}

{{/*
Container environment of a Spring app: envFrom the infra ConfigMap (Terraform config_env, optional) and the app's
ConfigMap, then each secret variable by key. (dict "root" $ "name" "<app>" "app" $appValues)
*/}}
{{- define "northline.springEnv" -}}
envFrom:
  - configMapRef:
      name: {{ include "northline.infraConfigMap" .root }}
      optional: true
  - configMapRef:
      name: {{ include "northline.appName" .name }}
{{- with .app.secretEnv }}
env:
{{- range $key, $required := . }}
  - name: {{ $key }}
    valueFrom:
      secretKeyRef:
        name: {{ include "northline.secretName" (dict "root" $.root "name" $.name) }}
        key: {{ $key }}
        {{- if not $required }}
        optional: true
        {{- end }}
{{- end }}
{{- end }}
{{- end }}

{{/* Checksum of everything that feeds an app's environment, so pods roll when it changes. */}}
{{- define "northline.configChecksum" -}}
{{- $parts := list (include "northline.appEnv" .) (toYaml .root.Values.configEnv) (toYaml (default (dict) .app.secretEnv)) -}}
{{- if .root.Values.secrets.create }}{{ $parts = append $parts (toYaml .root.Values.secrets.values) }}{{ end -}}
{{- if and (eq .name "auth") .root.Values.partners }}{{ $parts = append $parts (toYaml .root.Values.partners) }}{{ end -}}
{{- join "\n" $parts | sha256sum -}}
{{- end }}

{{/* S-17 edge. The DNS zone the public hosts live in: edge.zone, else the passkey RP id (dev.northline.ca, …). */}}
{{- define "northline.edgeZone" -}}
{{- default .Values.urls.webauthnRpId .Values.edge.zone -}}
{{- end }}

{{/* Gateway listener of a host: https-<host with dashes>. */}}
{{- define "northline.listenerName" -}}
{{- printf "https-%s" (. | replace "." "-") | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/*
TLS Secret of a host: one per host (HTTP-01), or the zone's wildcard Secret when edge.certManager.wildcard is on and
the host is in the zone (a merchant's own domain always gets its own). (dict "root" $ "host" "<host>" "custom" bool)
*/}}
{{- define "northline.tlsSecret" -}}
{{- $cm := .root.Values.edge.certManager -}}
{{- if and $cm.wildcard (not .custom) -}}
northline-tls-wildcard
{{- else -}}
{{- printf "northline-tls-%s" (.host | replace "." "-") | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end }}

{{/*
Response headers the edge sets on every route (HSTS and friends), as YAML name/value pairs; empty when off.
(dict "root" $ "custom" true) for a merchant's own domain: HSTS without includeSubDomains/preload — the merchant's other
subdomains are theirs, not ours to force onto HTTPS. A plain root context = our own hosts.
*/}}
{{- define "northline.responseHeaders" -}}
{{- $root := default . .root -}}
{{- $custom := and (hasKey . "custom") .custom -}}
{{- $e := $root.Values.edge -}}
{{- if $e.enabled -}}
{{- $h := list -}}
{{- if $e.hsts.enabled -}}
{{- $v := printf "max-age=%d" (int $e.hsts.maxAge) -}}
{{- if and $e.hsts.includeSubDomains (not $custom) }}{{ $v = printf "%s; includeSubDomains" $v }}{{ end -}}
{{- if and $e.hsts.preload (not $custom) }}{{ $v = printf "%s; preload" $v }}{{ end -}}
{{- $h = append $h (dict "name" "Strict-Transport-Security" "value" $v) -}}
{{- end -}}
{{- range $name, $value := $e.responseHeaders -}}
{{- $h = append $h (dict "name" $name "value" $value) -}}
{{- end -}}
{{- if $h }}{{ toYaml $h }}{{ end -}}
{{- end -}}
{{- end }}

{{/*
S-31: the api's custom-domain settings (docs/runbooks/custom-domains.md), as a YAML map of variables. The target is
pages.<zone> (urls.pages); with edge.domainReconciler.enabled the api writes shard Gateways, Certificates and HTTPRoutes
into this namespace (DOMAINS_EDGE_PROVIDER=kubernetes) and asks DNS over HTTPS / JNDI.
*/}}
{{- define "northline.domainsEnv" -}}
{{- $v := .Values -}}
{{- $r := $v.edge.domainReconciler -}}
{{- $env := dict -}}
{{- if $v.urls.pages -}}
{{- $_ := set $env "DOMAINS_TARGET_HOST" (include "northline.host" $v.urls.pages) -}}
{{- end -}}
{{- $_ := set $env "DOMAINS_BLOCKED_SUFFIXES" (join "," (concat (list "northline.ca" (include "northline.edgeZone" .)) $r.blockedSuffixes | uniq)) -}}
{{- if $r.edgeAddresses }}{{ $_ := set $env "DOMAINS_EDGE_ADDRESSES" (join "," $r.edgeAddresses) }}{{ end -}}
{{- if and $v.edge.enabled $r.enabled -}}
{{- $headers := dict -}}
{{- range (include "northline.responseHeaders" (dict "root" . "custom" true) | fromYamlArray) }}{{ $_ := set $headers .name .value }}{{ end -}}
{{- $_ := set $env "DOMAINS_EDGE_PROVIDER" "kubernetes" -}}
{{- $_ := set $env "DOMAINS_EDGE_NAMESPACE" .Release.Namespace -}}
{{- $_ := set $env "DOMAINS_EDGE_GATEWAY_CLASS" $v.edge.gateway.className -}}
{{- $_ := set $env "DOMAINS_EDGE_GATEWAY_PREFIX" $r.gatewayPrefix -}}
{{- $_ := set $env "DOMAINS_EDGE_LISTENERS_PER_GATEWAY" (toString $r.listenersPerGateway) -}}
{{- $_ := set $env "DOMAINS_MAX" (toString $r.maxDomains) -}}
{{- $_ := set $env "DOMAINS_ISSUE_PER_HOUR" (toString $r.issuePerHour) -}}
{{- $_ := set $env "DOMAINS_EDGE_ISSUER" $r.issuer.name -}}
{{- $_ := set $env "DOMAINS_EDGE_SERVICE" (include "northline.appName" "consumer") -}}
{{- $_ := set $env "DOMAINS_EDGE_SERVICE_PORT" (toString $v.apps.consumer.port) -}}
{{- $_ := set $env "DOMAINS_EDGE_RESPONSE_HEADERS" (toJson $headers) -}}
{{- $_ := set $env "DOMAINS_DNS_PROVIDER" $r.dns.provider -}}
{{- with $r.dns.dohUrl }}{{ $_ := set $env "DOMAINS_DOH_URL" . }}{{ end -}}
{{- with $r.dns.servers }}{{ $_ := set $env "DOMAINS_DNS_SERVERS" (join "," .) }}{{ end -}}
{{- end -}}
{{- toYaml $env -}}
{{- end }}

{{/* S-31: the ACME server of merchants' certificates — Let's Encrypt production in prod, staging everywhere else. */}}
{{- define "northline.customIssuerServer" -}}
{{- $server := .Values.edge.domainReconciler.issuer.server -}}
{{- if $server -}}
{{- $server -}}
{{- else if eq .Values.global.environment "prod" -}}
https://acme-v02.api.letsencrypt.org/directory
{{- else -}}
https://acme-staging-v02.api.letsencrypt.org/directory
{{- end -}}
{{- end }}

{{/*
S-111: the OpenTelemetry Collector's configuration (templates/otel-collector.yaml). Fixed processors — memory limit,
environment stamp, the scrub of personal data and secrets (S-112 adds the log redaction), batch — then the
environment's exporters (observability.collector.exporters / pipelines).
*/}}
{{- define "northline.collectorConfig" -}}
{{- $c := .Values.observability.collector -}}
{{- $processors := dict
  "memory_limiter" (dict "check_interval" "1s" "limit_mib" (int $c.memoryLimitMiB) "spike_limit_mib" (div (int $c.memoryLimitMiB) 5))
  "resource" (dict "attributes" (list (dict "key" "deployment.environment.name" "value" "${env:NORTHLINE_ENVIRONMENT}" "action" "upsert")))
  "attributes/scrub" (dict "actions" (list
      (dict "pattern" "^jdbc\\.params.*" "action" "delete")
      (dict "pattern" "^http\\.request\\.header\\.(authorization|cookie|x-xsrf-token|x-dev-user)$" "action" "delete")
      (dict "pattern" "^http\\.response\\.header\\.set-cookie$" "action" "delete")
      (dict "key" "enduser.id" "action" "delete")
      (dict "key" "user.email" "action" "delete")))
  "batch" (dict "send_batch_size" 1024 "timeout" "5s")
-}}
{{- $processors = merge $processors (include "northline.collectorExtraProcessors" . | fromYaml) (deepCopy $c.extraProcessors) -}}
{{- $pipelines := dict -}}
{{- range $signal := list "traces" "metrics" "logs" -}}
{{- $chain := list "memory_limiter" "resource" -}}
{{- if ne $signal "metrics" }}{{ $chain = append $chain "attributes/scrub" }}{{ end -}}
{{- $chain = concat $chain (get (include "northline.collectorSignalProcessors" $ | fromYaml) $signal | default list) (get $c.extraProcessorsIn $signal | default list) (list "batch") -}}
{{- $_ := set $pipelines $signal (dict "receivers" (list "otlp") "processors" $chain "exporters" (get $c.pipelines $signal)) -}}
{{- end -}}
{{- $config := dict
  "receivers" (dict "otlp" (dict "protocols" (dict "grpc" (dict "endpoint" "0.0.0.0:4317") "http" (dict "endpoint" "0.0.0.0:4318"))))
  "processors" $processors
  "exporters" $c.exporters
  "extensions" (dict "health_check" (dict "endpoint" "0.0.0.0:13133"))
  "service" (dict "extensions" (list "health_check") "pipelines" $pipelines "telemetry" (dict "logs" (dict "level" "info")))
-}}
{{- toYaml $config -}}
{{- end }}

{{/* Processors the chart adds for one signal only. YAML map signal → list. */}}
{{- define "northline.collectorSignalProcessors" -}}
logs: [transform/redact]
traces: [transform/redact]
{{- end }}

{{/*
S-112: the Collector's second line of defence (the apps already redact, platform Redactor): emails, North American
phone numbers, card-like digit runs, Canadian postal codes, bearer credentials and JWTs in log bodies and in log and
span attributes. RE2 has no look-behind, so these are coarser than the apps' rules; docs/runbooks/logging.md.
*/}}
{{- define "northline.collectorExtraProcessors" -}}
transform/redact:
  error_mode: ignore
  log_statements:
    - context: log
      statements:
        {{- range (include "northline.redactPatterns" . | fromYamlArray) }}
        - {{ printf "replace_pattern(body, %q, %q)" .regex .with | quote }}
        - {{ printf "replace_all_patterns(attributes, \"value\", %q, %q)" .regex .with | quote }}
        {{- end }}
  trace_statements:
    - context: span
      statements:
        {{- range (include "northline.redactPatterns" . | fromYamlArray) }}
        - {{ printf "replace_all_patterns(attributes, \"value\", %q, %q)" .regex .with | quote }}
        {{- end }}
{{- end }}

{{- define "northline.redactPatterns" -}}
- { regex: "(?i)(bearer|basic|dpop)\\s+[A-Za-z0-9._~+/=-]{8,}", with: "$$1 [REDACTED]" }
- { regex: "eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*", with: "[REDACTED]" }
- { regex: "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}", with: "[EMAIL]" }
- { regex: "\\b\\d(?:[ -]?\\d){12,18}\\b", with: "[CARD]" }
- { regex: "(\\+?1[ .-]?)?\\(?\\b[2-9]\\d{2}\\)?[ .-]?[2-9]\\d{2}[ .-]?\\d{4}\\b", with: "[PHONE]" }
- { regex: "\\b([ABCEGHJ-NPRSTVXY]\\d[ABCEGHJ-NPRSTV-Z]) ?\\d[ABCEGHJ-NPRSTV-Z]\\d\\b", with: "$$1 ***" }
{{- end }}
