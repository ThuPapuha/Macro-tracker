#!/usr/bin/env bash
# Sets up the Supabase project used by the app through the Supabase Management API,
# so the only thing a person has to create by hand is an access token.
#
#   supabase/provision.sh setup    find or create the project, wait until it is up,
#                                  apply schema.sql and enable anonymous sign-ins
#   supabase/provision.sh app-env  write SUPABASE_URL / SUPABASE_ANON_KEY for the APK build
#
# Environment: SUPABASE_ACCESS_TOKEN (required), SUPABASE_PROJECT_REF (optional, otherwise the
# project is found by name), SUPABASE_PROJECT_NAME (default "macro-tracker"), SUPABASE_REGION
# (optional region code such as "sa-east-1"; default: any region in the Americas).
set -euo pipefail
shopt -s inherit_errexit

API="${SUPABASE_API_URL:-https://api.supabase.com}/v1"
NAME="${SUPABASE_PROJECT_NAME:-macro-tracker}"
HERE="$(cd "$(dirname "$0")" && pwd)"
GITHUB_ENV="${GITHUB_ENV:-/dev/null}"
GITHUB_OUTPUT="${GITHUB_OUTPUT:-/dev/null}"
RESPONSE="$(mktemp)"
trap 'rm -f "$RESPONSE" "$RESPONSE.json"' EXIT

: "${SUPABASE_ACCESS_TOKEN:?Falta SUPABASE_ACCESS_TOKEN}"

# call METHOD PATH [curl args...] -> prints the body; fails on HTTP errors.
call() {
  local method=$1 path=$2 code
  shift 2
  code=$(curl -sS -o "$RESPONSE" -w '%{http_code}' -X "$method" "$API$path" \
    -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN" \
    -H "Content-Type: application/json" "$@") || return 1
  if [ "$code" -ge 400 ]; then
    echo "Supabase API $method $path -> HTTP $code: $(head -c 500 "$RESPONSE")" >&2
    return 1
  fi
  cat "$RESPONSE"
}

find_ref() {
  if [ -n "${SUPABASE_PROJECT_REF:-}" ]; then
    echo "$SUPABASE_PROJECT_REF"
    return
  fi
  call GET /projects | jq -r --arg name "$NAME" '[.[] | select(.name == $name)][0] | (.ref // .id) // empty'
}

create_project() {
  local org region
  org="${SUPABASE_ORG:-$(call GET /organizations | jq -r '.[0] | (.slug // .id) // empty')}"
  if [ -z "$org" ]; then
    # New accounts have no organization until one is created in the dashboard.
    echo "Creando una organización en Supabase…" >&2
    jq -n '{name: "Macro Tracker"}' > "$RESPONSE.json"
    org=$(call POST /organizations --data @"$RESPONSE.json" | jq -r '(.slug // .id) // empty') || org=""
    if [ -z "$org" ]; then
      echo "::error::No se pudo crear una organización. Crea una en https://supabase.com/dashboard/new y vuelve a correr el workflow." >&2
      return 1
    fi
  fi
  if [ -n "${SUPABASE_REGION:-}" ]; then
    region=$(jq -n --arg code "$SUPABASE_REGION" '{type: "specific", code: $code}')
  else
    region='{"type": "smartGroup", "code": "americas"}'
  fi
  # The database password is never needed: everything goes through the Management API.
  jq -n --arg name "$NAME" --arg org "$org" --arg pass "$(openssl rand -hex 24)" --argjson region "$region" \
    '{name: $name, organization_slug: $org, db_pass: $pass, region_selection: $region}' > "$RESPONSE.json"
  call POST /projects --data @"$RESPONSE.json" | jq -r '.ref // .id'
}

wait_until_ready() {
  local ref=$1 status
  for _ in $(seq 1 60); do
    status=$(call GET "/projects/$ref" | jq -r '.status')
    case "$status" in
      ACTIVE_HEALTHY)
        echo "Proyecto $ref listo."
        return 0
        ;;
      INACTIVE)
        # Free projects pause after a week without use.
        echo "El proyecto estaba pausado; reactivándolo…"
        call POST "/projects/$ref/restore" > /dev/null
        ;;
      INIT_FAILED | REMOVED | RESTORE_FAILED)
        echo "::error::El proyecto $ref está en estado $status." >&2
        return 1
        ;;
    esac
    echo "Esperando a que el proyecto esté listo ($status)…"
    sleep "${SUPABASE_POLL_SECONDS:-15}"
  done
  echo "::error::El proyecto $ref no estuvo listo a tiempo." >&2
  return 1
}

apply_schema() {
  local ref=$1 out
  jq -Rs '{query: .}' "$HERE/schema.sql" > "$RESPONSE.json"
  for attempt in 1 2 3 4 5; do
    # A rejected query can come back as HTTP 2xx with {"message": ...}.
    if out=$(call POST "/projects/$ref/database/query" --data @"$RESPONSE.json") &&
      ! jq -e 'type == "object" and has("message")' <<< "$out" > /dev/null; then
      echo "Esquema aplicado."
      return 0
    fi
    echo "No se pudo aplicar el esquema (intento $attempt): ${out:-}" >&2
    sleep "${SUPABASE_POLL_SECONDS:-20}"
  done
  echo "::error::No se pudo aplicar supabase/schema.sql." >&2
  return 1
}

setup() {
  local ref
  ref=$(find_ref)
  if [ -z "$ref" ]; then
    echo "Creando el proyecto \"$NAME\" en Supabase…"
    ref=$(create_project)
  fi
  echo "SUPABASE_PROJECT_REF=$ref" >> "$GITHUB_ENV"
  echo "ref=$ref" >> "$GITHUB_OUTPUT"

  wait_until_ready "$ref"
  apply_schema "$ref"
  if call PATCH "/projects/$ref/config/auth" --data '{"external_anonymous_users_enabled": true}' > /dev/null; then
    echo "Login anónimo activado."
  else
    echo "::warning::Activa a mano en Supabase: Authentication → Sign In / Providers → Allow anonymous sign-ins."
  fi
}

app_env() {
  local ref key
  ref=$(find_ref)
  if [ -z "$ref" ]; then
    echo "::warning::Todavía no hay proyecto de Supabase: el APK se compila sin grupos ni IA del servidor."
    return 0
  fi
  key="${SUPABASE_ANON_KEY:-}"
  if [ -z "$key" ]; then
    # Client-side key (publishable, or the legacy anon key). It is meant to be public.
    key=$(call GET "/projects/$ref/api-keys?reveal=false" |
      jq -r '([.[] | select(.type == "publishable")][0].api_key) // ([.[] | select(.name == "anon")][0].api_key) // empty')
  fi
  if [ -z "$key" ]; then
    echo "::warning::No se encontró la clave pública del proyecto $ref."
    return 0
  fi
  echo "SUPABASE_URL=https://$ref.supabase.co" >> "$GITHUB_ENV"
  echo "SUPABASE_ANON_KEY=$key" >> "$GITHUB_ENV"
  echo "APK conectado al proyecto $ref."
}

case "${1:-}" in
  setup) setup ;;
  app-env) app_env ;;
  *)
    echo "Uso: $0 setup|app-env" >&2
    exit 2
    ;;
esac
