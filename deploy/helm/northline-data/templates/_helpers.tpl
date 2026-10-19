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

{{/* The Postgres backup store (CloudNativePG barmanObjectStore without serverName/wal/data): GCS as the workload
identity, or S3-compatible with static keys. Shared by the backups and a recovery's externalCluster. */}}
{{- define "data.pgStore" -}}
{{- $b := .Values.postgres.backup -}}
{{- if eq $b.provider "gcs" -}}
destinationPath: gs://{{ $b.bucket }}
googleCredentials:
  gkeEnvironment: true
{{- else -}}
destinationPath: s3://{{ $b.bucket }}/
endpointURL: {{ required "postgres.backup.s3.endpointURL (terraform output data_values)" $b.s3.endpointURL }}
s3Credentials:
  accessKeyId: { name: {{ $b.s3.credentialsSecret }}, key: ACCESS_KEY_ID }
  secretAccessKey: { name: {{ $b.s3.credentialsSecret }}, key: ACCESS_SECRET_KEY }
  region: { name: {{ $b.s3.credentialsSecret }}, key: REGION }
{{- end -}}
{{- end }}
