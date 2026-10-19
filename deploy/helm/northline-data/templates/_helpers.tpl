{{/* Common labels. Call with (dict "root" $ "name" "<service>"). */}}
{{- define "data.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: northline-data
{{ include "data.selectorLabels" . }}
{{- end }}

{{- define "data.selectorLabels" -}}
app.kubernetes.io/name: northline-{{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{/* Pin to the environment's zone (the volumes are zonal disks); nothing when pinToZone is false (OVHcloud). */}}
{{- define "data.nodeSelector" -}}
{{- if .Values.pinToZone }}
nodeSelector:
  topology.kubernetes.io/zone: {{ required "zone: the cluster's zone (terraform output data_values)" .Values.zone | quote }}
{{- end }}
{{- end }}

{{/* Remote key of a credential (Terraform data_values.externalSecrets.remoteKeys). */}}
{{- define "data.remoteKey" -}}
{{- $k := get .root.Values.externalSecrets.remoteKeys .key -}}
{{- required (printf "externalSecrets.remoteKeys.%s (terraform output data_values)" .key) $k -}}
{{- end }}
