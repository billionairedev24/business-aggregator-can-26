{{/* Checks and names shared by the templates. */}}
{{- define "gitops.env" -}}
{{- if not (has .Values.environment (list "dev" "staging" "prod" "local")) -}}
{{- fail (printf "environment must be dev, staging, prod or local (got %q)" .Values.environment) -}}
{{- end -}}
{{- if not (has .Values.cloud (list "aws" "gcp" "azure" "kind")) -}}
{{- fail (printf "cloud must be aws, gcp, azure or kind (got %q)" .Values.cloud) -}}
{{- end -}}
{{- if and (eq .Values.cloud "kind") (ne .Values.environment "local") -}}
{{- fail "cloud=kind is the local rehearsal: only with environment=local" -}}
{{- end -}}
{{- if not .Values.repoURL -}}
{{- fail "repoURL is required (the GitHub or GitLab URL Argo CD clones)" -}}
{{- end -}}
{{- /* Automated sync is for dev (and the local rehearsal) only: staging and prod deploy when a deployer syncs. */ -}}
{{- if and .Values.sync.automated (has .Values.environment (list "staging" "prod")) -}}
{{- fail (printf "sync.automated is refused in %s: staging and prod sync by hand after approval (docs/runbooks/gitops.md)" .Values.environment) -}}
{{- end -}}
{{- .Values.environment -}}
{{- end }}

{{- define "gitops.namespace" -}}
{{- default (printf "northline-%s" .Values.environment) .Values.northline.namespace -}}
{{- end }}

{{/* Project names: the root (Applications and AppProjects), the platform add-ons, the northline workloads. */}}
{{- define "gitops.project.gitops" -}}northline-{{ .Values.environment }}-gitops{{- end }}
{{- define "gitops.project.platform" -}}northline-{{ .Values.environment }}-platform{{- end }}
{{- define "gitops.project.app" -}}northline-{{ .Values.environment }}{{- end }}

{{- define "gitops.destination" -}}
server: {{ .Values.destination.server }}
{{- end }}

{{/* Common metadata of every child Application. (dict "root" $ "wave" "<n>" ["annotations" (dict …)]) */}}
{{- define "gitops.appMeta" -}}
labels:
  app.kubernetes.io/part-of: northline
  app.kubernetes.io/managed-by: argocd-app-of-apps
  northline.ca/environment: {{ .root.Values.environment | quote }}
  northline.ca/cloud: {{ .root.Values.cloud | quote }}
annotations:
  argocd.argoproj.io/sync-wave: {{ .wave | quote }}
  {{- range $k, $v := merge (default (dict) .annotations) .root.Values.notifications.subscriptions }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
{{- /* Cascade deletion only where Argo CD syncs by itself: removing an Application from Git in staging/prod
       leaves its workloads running until someone deletes them on purpose. */ -}}
{{- if .root.Values.sync.automated }}
finalizers:
  - resources-finalizer.argocd.argoproj.io
{{- end }}
{{- end }}

{{/* syncPolicy of a workload Application. (dict "root" $ "options" (list …)) */}}
{{- define "gitops.syncPolicy" -}}
{{- $s := .root.Values.sync -}}
{{- if $s.automated }}
automated:
  prune: {{ $s.prune }}
  selfHeal: {{ $s.selfHeal }}
{{- end }}
syncOptions:
  {{- toYaml .options | nindent 2 }}
retry:
  {{- toYaml $s.retry | nindent 2 }}
{{- end }}
