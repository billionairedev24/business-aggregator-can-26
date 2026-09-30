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
{{- $_ := set $env "SPRING_PROFILES_ACTIVE" (include "northline.springProfiles" $root) -}}
{{- $_ := set $env "AUTH_ISSUER" $v.urls.auth -}}
{{- $_ := set $env "AUTH_INTERNAL_URL" (printf "http://%s:%d" (include "northline.appName" "auth") (int $v.apps.auth.port)) -}}
{{- $_ := set $env "API_URL" (printf "http://%s:%d" (include "northline.appName" "api") (int $v.apps.api.port)) -}}
{{- $_ := set $env "STUDIO_ORIGIN" $v.urls.studio -}}
{{- $_ := set $env "CONSUMER_ORIGIN" $v.urls.consumer -}}
{{- $_ := set $env "CONSOLE_ORIGIN" $v.urls.console -}}
{{- $_ := set $env "WEBAUTHN_RP_ID" $v.urls.webauthnRpId -}}
{{- $_ := set $env "API_PUBLIC_URL" $v.urls.api -}}
{{- $_ := set $env "SERVER_PORT" (toString .app.port) -}}
{{- if and (eq .name "auth") $v.partners -}}
{{- /* S-30: northline.oauth.partners from the ConfigMap northline-auth-partners (templates/auth-partners.yaml). */ -}}
{{- $_ := set $env "SPRING_CONFIG_ADDITIONAL_LOCATION" "optional:file:/config/partners/" -}}
{{- end -}}
{{- if and (eq .name "api") $v.migrations.enabled -}}
{{- /* S-16: the migration Job owns Flyway; the api only runs against the migrated schema. */ -}}
{{- $_ := set $env "SPRING_FLYWAY_ENABLED" "false" -}}
{{- end -}}
{{- $env = merge (deepCopy (default (dict) .app.env)) (deepCopy (default (dict) $v.env)) $env -}}
{{- else if eq .app.type "static" -}}
{{- $_ := set $env "NL_AUTH_ORIGIN" $v.urls.auth -}}
{{- $env = merge (deepCopy (default (dict) .app.env)) $env -}}
{{- else -}}
{{- $_ := set $env "PORT" (toString .app.port) -}}
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

{{/* Response headers the edge sets on every route (HSTS and friends), as YAML name/value pairs; empty when off. */}}
{{- define "northline.responseHeaders" -}}
{{- $e := .Values.edge -}}
{{- if $e.enabled -}}
{{- $h := list -}}
{{- if $e.hsts.enabled -}}
{{- $v := printf "max-age=%d" (int $e.hsts.maxAge) -}}
{{- if $e.hsts.includeSubDomains }}{{ $v = printf "%s; includeSubDomains" $v }}{{ end -}}
{{- if $e.hsts.preload }}{{ $v = printf "%s; preload" $v }}{{ end -}}
{{- $h = append $h (dict "name" "Strict-Transport-Security" "value" $v) -}}
{{- end -}}
{{- range $name, $value := $e.responseHeaders -}}
{{- $h = append $h (dict "name" $name "value" $value) -}}
{{- end -}}
{{- if $h }}{{ toYaml $h }}{{ end -}}
{{- end -}}
{{- end }}
