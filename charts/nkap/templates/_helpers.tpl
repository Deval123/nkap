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
The environment every workload that runs the gateway's jar needs: the database connection,
the public base URL, and every configured MTN and M-Pesa installation. Shared between templates/deployment.yaml and
templates/keyinit-job.yaml so the two can never drift -- a key-init Job that saw a
different database, or a different MTN installation list, than the gateway it is
provisioning a key for would be a bug this template makes impossible to introduce by hand.

Names are exactly what nkap-standalone.compose.yaml and application.yml already use
(NKAP_DB_URL, NKAP_PROVIDER_MTN_<COUNTRY>_*) -- see values.yaml's own header for why.
*/}}
{{- define "nkap.env" -}}
- name: NKAP_SECRETS_DIR
  value: {{ include "nkap.credentialsDir" . | quote }}
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
{{- if .Values.publicBaseUrl }}
- name: NKAP_PUBLIC_BASE_URL
  value: {{ .Values.publicBaseUrl | quote }}
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
{{- /*
M-Pesa: one slot, Kenya, because that is all application.yml declares. Unlike the MTN range
above, this refuses what the gateway would not read, rather than rendering it: a second
entry, or a country other than ke, would produce NKAP_PROVIDER_MPESA_* variables no property
names, and the installation would silently not exist. values.yaml says why there is one slot.
*/}}
{{- $mpesa := .Values.provider.mpesa.installations | default list }}
{{- if gt (len $mpesa) 1 }}
{{- fail (printf "provider.mpesa.installations has %d entries, and at most one is allowed -- the gateway has exactly one M-Pesa slot, Kenya (ke). Whether another M-Pesa market shares Safaricom's API is recorded as unknown in docs/providers/m-pesa.md, so a second installation is not known to mean anything, and nothing in the gateway would read it" (len $mpesa)) }}
{{- end }}
{{- range $i, $installation := $mpesa }}
{{- $country := required (printf "provider.mpesa.installations[%d].country is required -- the only M-Pesa slot is Kenya, so set it to ke" $i) $installation.country }}
{{- if ne ($country | lower) "ke" }}
{{- fail (printf "provider.mpesa.installations[%d].country is %q, but the gateway's only M-Pesa slot is Kenya (ke) -- an installation for any other country would render variables nothing reads, and would silently not exist (see values.yaml)" $i $country) }}
{{- end }}
{{- $publicBaseUrl := required (printf "publicBaseUrl is required once an M-Pesa installation is configured (provider.mpesa.installations[%d], country %s) -- it becomes nkap.public-base-url, the address M-Pesa's callback reaches. The callback is the only way the gateway resolves an M-Pesa payment whose submission was lost, and the gateway refuses to start an M-Pesa installation without it" $i $country) $.Values.publicBaseUrl }}
{{- $secret := required (printf "provider.mpesa.installations[%d].existingSecret is required (country %s) -- create a Secret holding its consumer key, consumer secret and passkey, and name it here (see charts/nkap/README.md's credential rule)" $i $country) $installation.existingSecret }}
- name: NKAP_PROVIDER_MPESA_KE_COUNTRY
  value: {{ $country | lower | quote }}
- name: NKAP_PROVIDER_MPESA_KE_CURRENCY
  value: {{ required (printf "provider.mpesa.installations[%d].currency is required (country %s)" $i $country) $installation.currency | quote }}
- name: NKAP_PROVIDER_MPESA_KE_BASE_URL
  value: {{ required (printf "provider.mpesa.installations[%d].baseUrl is required (country %s)" $i $country) $installation.baseUrl | quote }}
{{- $shortCode := required (printf "provider.mpesa.installations[%d].businessShortCode is required (country %s) -- the shortcode payments are collected to" $i $country) $installation.businessShortCode }}
{{- if not (kindIs "string" $shortCode) }}
{{- fail (printf "provider.mpesa.installations[%d].businessShortCode must be quoted, e.g. \"174379\" -- unquoted, YAML reads it as a number and Helm renders a seven-digit one as %v, which the gateway refuses" $i $shortCode) }}
{{- end }}
- name: NKAP_PROVIDER_MPESA_KE_BUSINESS_SHORT_CODE
  value: {{ $shortCode | quote }}
- name: NKAP_PROVIDER_MPESA_KE_REQUEST_TIMEOUT
  value: {{ $installation.requestTimeout | default "PT20S" | quote }}
{{- if eq (include "nkap.mpesaCredentialsAs" (list $i $installation)) "env" }}
- name: NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: {{ $installation.consumerKeySecretKey | default "consumer-key" }}
- name: NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: {{ $installation.consumerSecretSecretKey | default "consumer-secret" }}
- name: NKAP_PROVIDER_MPESA_KE_PASSKEY
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: {{ $installation.passkeySecretKey | default "passkey" }}
{{- end }}
{{- end }}
{{- end -}}

{{/*
Where the gateway imports credential files from: NKAP_SECRETS_DIR, rendered by "nkap.env" above,
and the mount path of every credential volume below, both from this one definition so the two
can never disagree. Not the gateway's own default, /run/secrets: in the gateway's image /var/run
is a link to /run, so /run/secrets is where Kubernetes mounts the pod's service-account token
(/var/run/secrets/kubernetes.io/serviceaccount), and the gateway imports that directory's nested
files too. A directory of its own holds only what this chart puts there. CI's helm job fails if
this is ever at or under /run or /var/run.
*/}}
{{- define "nkap.credentialsDir" -}}
/etc/nkap/credentials
{{- end -}}

{{/*
How an M-Pesa installation's three credentials reach the gateway: "files" (the default) or "env".
Called with (list index installation). Any other value fails, naming the two allowed, rather than
falling through to one of them.
*/}}
{{- define "nkap.mpesaCredentialsAs" -}}
{{- $i := index . 0 }}
{{- $installation := index . 1 }}
{{- $as := $installation.credentialsAs | default "files" }}
{{- if not (has $as (list "files" "env")) }}
{{- fail (printf "provider.mpesa.installations[%d].credentialsAs is %q, but it must be \"files\" (the default: the credentials are mounted as files, the form the gateway re-reads) or \"env\" (environment variables, read once at startup)" $i (toString $as)) }}
{{- end }}
{{- $as -}}
{{- end -}}

{{/*
The credential volumes every workload that runs the gateway's jar needs, beside "nkap.env" and for
the same reason: the key-init Job starts the same application context, which validates every
configured installation, so it must see the credentials exactly as the gateway does.

One volume per M-Pesa installation whose credentials are files. Its items map each key of the
installation's Secret, which values can rename, to a file named after the variable it replaces
(NKAP_PROVIDER_MPESA_KE_PASSKEY and so on). That file name is fixed by the gateway, not by values:
the gateway reads a file named exactly like the variable. Those three variables are then not
rendered in "nkap.env", because the gateway refuses to start when one name is both a variable
and a file.
*/}}
{{- define "nkap.credentialVolumes" -}}
{{- range $i, $installation := .Values.provider.mpesa.installations | default list }}
{{- if eq (include "nkap.mpesaCredentialsAs" (list $i $installation)) "files" }}
- name: mpesa-ke-credentials
  secret:
    secretName: {{ required (printf "provider.mpesa.installations[%d].existingSecret is required -- create a Secret holding its consumer key, consumer secret and passkey, and name it here (see charts/nkap/README.md's credential rule)" $i) $installation.existingSecret }}
    items:
      - key: {{ $installation.consumerKeySecretKey | default "consumer-key" }}
        path: NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY
      - key: {{ $installation.consumerSecretSecretKey | default "consumer-secret" }}
        path: NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET
      - key: {{ $installation.passkeySecretKey | default "passkey" }}
        path: NKAP_PROVIDER_MPESA_KE_PASSKEY
{{- end }}
{{- end }}
{{- end -}}

{{/* The mounts for "nkap.credentialVolumes", at "nkap.credentialsDir", read-only. */}}
{{- define "nkap.credentialVolumeMounts" -}}
{{- range $i, $installation := .Values.provider.mpesa.installations | default list }}
{{- if eq (include "nkap.mpesaCredentialsAs" (list $i $installation)) "files" }}
- name: mpesa-ke-credentials
  mountPath: {{ include "nkap.credentialsDir" $ }}
  readOnly: true
{{- end }}
{{- end }}
{{- end -}}
