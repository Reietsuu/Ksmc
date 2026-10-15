{{- define "ksmc.fullname" -}}
{{- if contains "ksmc" .Release.Name -}}{{ .Release.Name | trunc 63 | trimSuffix "-" }}{{- else -}}{{ printf "%s-ksmc" .Release.Name | trunc 63 | trimSuffix "-" }}{{- end -}}
{{- end -}}

{{- define "ksmc.labels" -}}
app.kubernetes.io/name: ksmc
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "ksmc.controllerSelector" -}}
app.kubernetes.io/name: ksmc
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: controller
{{- end -}}

{{- define "ksmc.natsUrl" -}}
{{- if .Values.nats.enabled -}}nats://{{ .Values.nats.fullnameOverride }}.{{ .Release.Namespace }}.svc:4222{{- else -}}{{ required "nats.externalUrl est requis quand nats.enabled=false" .Values.nats.externalUrl }}{{- end -}}
{{- end -}}
