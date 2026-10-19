#!/usr/bin/env bash
# Sets up the Supabase project used by the app through the Supabase Management API,
# so the only thing a person has to create by hand is an access token.
#
#   supabase/provision.sh setup    find or create the project, wait until it is up,
#                                  apply schema.sql and enable anonymous sign-ins
#   supabase/provision.sh app-env  write SUPABASE_URL / SUPABASE_ANON_KEY for the APK build
#   supabase/provision.sh smoke-test  sign in as a throwaway user, call the API and the Gemini
#                                     function like the app does, then delete that user
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

# Access tokens can be limited to the organizations that existed when they were created.
TOKEN_HINT="Si tu token de Supabase no ve tu organización, crea uno nuevo en https://supabase.com/dashboard/account/tokens con acceso a todas tus organizaciones, reemplaza el secreto SUPABASE_ACCESS_TOKEN en GitHub y vuelve a correr el workflow. También puedes crear el proyecto \"$NAME\" a mano en https://supabase.com/dashboard/new."

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
  # Match the name case-insensitively; if the account has a single project, use that one.
  call GET /projects | jq -r --arg name "$NAME" '
    ([.[] | select((.name | ascii_downcase) == ($name | ascii_downcase))][0]
      // (if length == 1 then .[0] else null end))
    | if . == null then empty else (.ref // .id) end'
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
      echo "::error::$TOKEN_HINT" >&2
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
  if ! call POST /projects --data @"$RESPONSE.json" | jq -r '.ref // .id'; then
    echo "::error::No se pudo crear el proyecto. $TOKEN_HINT" >&2
    return 1
  fi
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

cleanup_smoke_user() {
  # The id comes from Supabase, but only ever splice a well-formed UUID into SQL.
  [[ "$SMOKE_UID" =~ ^[0-9a-f-]{36}$ ]] || return 0
  jq -n --arg q "delete from auth.users where id = '$SMOKE_UID'" '{query: $q}' > "$RESPONSE.json"
  call POST "/projects/$SMOKE_REF/database/query" --data @"$RESPONSE.json" > /dev/null || true
  rm -f "$RESPONSE" "$RESPONSE.json"
}

smoke_test() {
  local ref url key session token uid code
  ref=$(find_ref)
  url="${SUPABASE_PROJECT_URL:-https://$ref.supabase.co}"
  key=$(call GET "/projects/$ref/api-keys?reveal=false" |
    jq -r '([.[] | select(.type == "publishable")][0].api_key) // ([.[] | select(.name == "anon")][0].api_key) // empty')

  session=$(curl -sS -X POST "$url/auth/v1/signup" -H "apikey: $key" -H "Content-Type: application/json" -d '{"data": {}}')
  token=$(jq -r '.access_token // empty' <<< "$session")
  uid=$(jq -r '.user.id // empty' <<< "$session")
  if [ -z "$token" ]; then
    echo "::error::Login anónimo falló: $(head -c 300 <<< "$session")"
    return 1
  fi
  # Remove the throwaway user (and its rows) whatever happens next.
  SMOKE_REF=$ref SMOKE_UID=$uid
  trap cleanup_smoke_user EXIT
  echo "Login anónimo: ok"

  code=$(curl -sS -o "$RESPONSE" -w '%{http_code}' -X POST "$url/rest/v1/rpc/my_groups" \
    -H "apikey: $key" -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d '{}')
  if [ "$code" != 200 ] || [ "$(cat "$RESPONSE")" != "[]" ]; then
    echo "::error::La función my_groups respondió HTTP $code: $(head -c 300 "$RESPONSE")"
    return 1
  fi
  echo "Base de datos: ok"

  # Same request shape the app sends: JSON output constrained by a responseSchema.
  jq -n '{
    contents: [{role: "user", parts: [{text: "Comida a estimar: 1 manzana mediana"}]}],
    systemInstruction: {parts: [{text: "Estima calorías y macros de la comida. Responde en español."}]},
    generationConfig: {
      responseMimeType: "application/json",
      responseSchema: {
        type: "OBJECT",
        properties: {
          name: {type: "STRING"}, calories: {type: "NUMBER"},
          protein: {type: "NUMBER"}, carbs: {type: "NUMBER"}, fat: {type: "NUMBER"}
        },
        required: ["name", "calories", "protein", "carbs", "fat"]
      }
    }
  }' > "$RESPONSE.json"
  local answer attempt
  for attempt in 1 2 3; do
    code=$(curl -sS -o "$RESPONSE" -w '%{http_code}' -X POST "$url/functions/v1/gemini" \
      -H "apikey: $key" -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
      --data @"$RESPONSE.json")
    answer=$(jq -r '[.candidates[0].content.parts[]? | select(.thought != true) | .text // empty] | join("")' "$RESPONSE" 2> /dev/null || true)
    if [ "$code" = 200 ] && jq -e '.calories > 0' <<< "$answer" > /dev/null 2>&1; then
      break
    fi
    # Overloaded models (5xx) usually recover quickly; anything else will not.
    if [ "$attempt" = 3 ] || [ "$code" -lt 500 ]; then
      echo "::error::La IA respondió HTTP $code: $(head -c 400 "$RESPONSE")"
      return 1
    fi
    echo "Gemini respondió HTTP $code, reintentando en 20 s…"
    sleep "${SUPABASE_POLL_SECONDS:-20}"
  done
  echo "Gemini (${GEMINI_MODEL:-gemini-3.6-flash}): ok -> $answer"
}

case "${1:-}" in
  setup) setup ;;
  app-env) app_env ;;
  smoke-test) smoke_test ;;
  *)
    echo "Uso: $0 setup|app-env|smoke-test" >&2
    exit 2
    ;;
esac
