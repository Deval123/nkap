{{/*
Standard name/label helpers, the same shape every Helm chart uses -- nothing nkap-specific
here, so a reader who knows Helm can skip straight to "nkap.env" below and the templates
that matter.
*/}}

{{- define "nkap.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "nkap.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "nkap.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "nkap.labels" -}}
helm.sh/chart: {{ include "nkap.chart" . }}
{{ include "nkap.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "nkap.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nkap.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
The environment every workload that runs the gateway's jar needs: the database connection
and every configured MTN installation. Shared between templates/deployment.yaml and
templates/keyinit-job.yaml so the two can never drift -- a key-init Job that saw a
different database, or a different MTN installation list, than the gateway it is
provisioning a key for would be a bug this template makes impossible to introduce by hand.

Names are exactly what nkap-standalone.compose.yaml and application.yml already use
(NKAP_DB_URL, NKAP_PROVIDER_MTN_<COUNTRY>_*) -- see values.yaml's own header for why.
*/}}
{{- define "nkap.env" -}}
- name: NKAP_DB_URL
  value: {{ printf "jdbc:postgresql://%s:%d/%s" (required "database.host is required -- point it at your own PostgreSQL (see charts/nkap/README.md)" .Values.database.host) (.Values.database.port | int) .Values.database.name | quote }}
- name: NKAP_DB_USER
  value: {{ .Values.database.username | quote }}
- name: NKAP_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "database.existingSecret is required -- create a Secret holding the database password and name it here (see charts/nkap/README.md's credential rule)" .Values.database.existingSecret }}
      key: {{ .Values.database.existingSecretPasswordKey | default "password" }}
{{- if .Values.provider.default }}
- name: NKAP_PROVIDER_DEFAULT
  value: {{ .Values.provider.default | quote }}
{{- end }}
{{- range $i, $installation := .Values.provider.mtn.installations }}
{{- $country := required (printf "provider.mtn.installations[%d].country is required" $i) $installation.country }}
{{- $prefix := printf "NKAP_PROVIDER_MTN_%s" ($country | upper) }}
{{- $secret := required (printf "provider.mtn.installations[%d].existingSecret is required (country %s) -- create a Secret holding its subscription key, API user and API key, and name it here (see charts/nkap/README.md's credential rule)" $i $country) $installation.existingSecret }}
- name: {{ $prefix }}_COUNTRY
  value: {{ $country | quote }}
- name: {{ $prefix }}_CURRENCY
  value: {{ required (printf "provider.mtn.installations[%d].currency is required (country %s)" $i $country) $installation.currency | quote }}
- name: {{ $prefix }}_BASE_URL
  value: {{ required (printf "provider.mtn.installations[%d].baseUrl is required (country %s)" $i $country) $installation.baseUrl | quote }}
- name: {{ $prefix }}_TARGET_ENVIRONMENT
  value: {{ required (printf "provider.mtn.installations[%d].targetEnvironment is required (country %s)" $i $country) $installation.targetEnvironment | quote }}
- name: {{ $prefix }}_REQUEST_TIMEOUT
  value: {{ $installation.requestTimeout | default "PT20S" | quote }}
- name: {{ $prefix }}_SUBSCRIPTION_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: {{ $installation.subscriptionKeySecretKey | default "subscription-key" }}
- name: {{ $prefix }}_API_USER
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: {{ $installation.apiUserSecretKey | default "api-user" }}
- name: {{ $prefix }}_API_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: {{ $installation.apiKeySecretKey | default "api-key" }}
{{- if $installation.disbursement }}
{{- $disbSecret := required (printf "provider.mtn.installations[%d].disbursement.existingSecret is required once a disbursement block is present (country %s) -- omit the whole disbursement block for a collections-only installation" $i $country) $installation.disbursement.existingSecret }}
- name: {{ $prefix }}_DISBURSEMENT_SUBSCRIPTION_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $disbSecret }}
      key: {{ $installation.disbursement.subscriptionKeySecretKey | default "subscription-key" }}
- name: {{ $prefix }}_DISBURSEMENT_API_USER
  valueFrom:
    secretKeyRef:
      name: {{ $disbSecret }}
      key: {{ $installation.disbursement.apiUserSecretKey | default "api-user" }}
- name: {{ $prefix }}_DISBURSEMENT_API_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $disbSecret }}
      key: {{ $installation.disbursement.apiKeySecretKey | default "api-key" }}
{{- end }}
{{- end }}
{{- end -}}
