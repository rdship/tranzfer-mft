{{/*
Expand the name of the chart.
*/}}
{{- define "mft.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "mft.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s" .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "mft.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Database URL
*/}}
{{- define "mft.databaseUrl" -}}
jdbc:postgresql://{{ .Values.global.postgresql.host }}:{{ .Values.global.postgresql.port }}/{{ .Values.global.postgresql.database }}
{{- end }}

{{/*
Common environment variables shared across all backend services
*/}}
{{- define "mft.commonEnv" -}}
- name: DATABASE_URL
  value: {{ include "mft.databaseUrl" . | quote }}
- name: DB_USERNAME
  value: {{ .Values.global.postgresql.username | quote }}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: mft-secrets
      key: db-password
- name: RABBITMQ_HOST
  value: {{ .Values.global.rabbitmq.host | quote }}
- name: RABBITMQ_PORT
  value: {{ .Values.global.rabbitmq.port | quote }}
- name: JWT_SECRET
  valueFrom:
    secretKeyRef:
      name: mft-secrets
      key: jwt-secret
- name: CONTROL_API_KEY
  valueFrom:
    secretKeyRef:
      name: mft-secrets
      key: control-api-key
- name: CLUSTER_ID
  value: {{ .Values.global.clusterId | quote }}
{{- end }}
