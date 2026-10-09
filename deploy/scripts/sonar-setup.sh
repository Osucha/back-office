#!/bin/sh
# Quality gate as code (GR-Q3, ADR 0027): creates/updates the SonarQube project `back-office`, the quality
# gate "Back Office way" and the new-code definition through the SonarQube Web API. Idempotent: run it
# again after changing a threshold and the gate is rebuilt with the new values.
#
#   SONAR_HOST_URL=https://sonar.example.com SONAR_ADMIN_TOKEN=squ_... sh deploy/scripts/sonar-setup.sh
#
# Authentication (a user with "Administer System"):
#   SONAR_ADMIN_TOKEN                          user token, or
#   SONAR_ADMIN_USER + SONAR_ADMIN_PASSWORD    login and password
#
# Settings (environment, defaults in brackets):
#   SONAR_HOST_URL                 [http://localhost:9100]  (deploy/compose, profile quality)
#   SONAR_PROJECT_KEY              [back-office]
#   SONAR_PROJECT_NAME             [Back Office]
#   SONAR_GATE_NAME                [Back Office way]
#   SONAR_GATE_NEW_COVERAGE        [70]    minimum coverage on new code, %
#   SONAR_GATE_NEW_DUPLICATION     [3]     maximum duplicated lines on new code, %
#   SONAR_GATE_NEW_RATING          [A]     worst accepted maintainability/reliability/security rating on new code
#   SONAR_GATE_HOTSPOTS_REVIEWED   [100]   minimum share of reviewed security hotspots on new code, %
#   SONAR_GATE_ZERO_NEW_ISSUES     [false] true = also fail on ANY new issue (the strict "Sonar way" rule);
#                                          false = only new blocker/critical (high) issues fail
#   SONAR_NEW_CODE                 [PREVIOUS_VERSION]  new-code definition of the project: PREVIOUS_VERSION,
#                                          NUMBER_OF_DAYS:<n> or REFERENCE_BRANCH:<branch>
#   SONAR_CREATE_TOKEN             [false] true = also generate a project analysis token and print it once
#                                          (store it as the masked CI variable SONAR_TOKEN)
#
# Needs: POSIX sh, curl, sed, grep. No jq.
set -eu

HOST="${SONAR_HOST_URL:-http://localhost:9100}"
HOST="${HOST%/}"
PROJECT_KEY="${SONAR_PROJECT_KEY:-back-office}"
PROJECT_NAME="${SONAR_PROJECT_NAME:-Back Office}"
GATE="${SONAR_GATE_NAME:-Back Office way}"
NEW_COVERAGE="${SONAR_GATE_NEW_COVERAGE:-70}"
NEW_DUPLICATION="${SONAR_GATE_NEW_DUPLICATION:-3}"
NEW_RATING="${SONAR_GATE_NEW_RATING:-A}"
HOTSPOTS_REVIEWED="${SONAR_GATE_HOTSPOTS_REVIEWED:-100}"
ZERO_NEW_ISSUES="${SONAR_GATE_ZERO_NEW_ISSUES:-false}"
NEW_CODE="${SONAR_NEW_CODE:-PREVIOUS_VERSION}"
CREATE_TOKEN="${SONAR_CREATE_TOKEN:-false}"

if [ -n "${SONAR_ADMIN_TOKEN:-}" ]; then
  AUTH="${SONAR_ADMIN_TOKEN}:"
elif [ -n "${SONAR_ADMIN_USER:-}" ] && [ -n "${SONAR_ADMIN_PASSWORD:-}" ]; then
  AUTH="${SONAR_ADMIN_USER}:${SONAR_ADMIN_PASSWORD}"
else
  echo "sonar-setup: set SONAR_ADMIN_TOKEN, or SONAR_ADMIN_USER and SONAR_ADMIN_PASSWORD" >&2
  exit 2
fi

# Rating letter -> worst accepted numeric value (A=1 ... E=5); the condition fails when the rating is greater.
case "$NEW_RATING" in
  A) RATING=1 ;; B) RATING=2 ;; C) RATING=3 ;; D) RATING=4 ;; E) RATING=5 ;;
  *) echo "sonar-setup: SONAR_GATE_NEW_RATING must be A..E" >&2; exit 2 ;;
esac

log() { echo "sonar-setup: $*"; }

# api GET|POST <path> [curl --data-urlencode args...]  -> response body on stdout, fails on HTTP >= 400
api() {
  method="$1"; path="$2"; shift 2
  if [ "$method" = "GET" ]; then
    curl -sS --fail-with-body -u "$AUTH" -G "$HOST/api/$path" "$@"
  else
    curl -sS --fail-with-body -u "$AUTH" -X POST "$HOST/api/$path" "$@"
  fi
}

status=$(curl -fsS "$HOST/api/system/status" || true)
case "$status" in
  *'"status":"UP"'*) ;;
  *) echo "sonar-setup: SonarQube at $HOST is not UP: $status" >&2; exit 1 ;;
esac
api GET authentication/validate | grep -q '"valid":true' || { echo "sonar-setup: credentials rejected" >&2; exit 1; }

# --- project -------------------------------------------------------------------------------------------
if api GET projects/search --data-urlencode "projects=$PROJECT_KEY" | grep -q "\"key\":\"$PROJECT_KEY\""; then
  log "project $PROJECT_KEY exists"
else
  api POST projects/create --data-urlencode "project=$PROJECT_KEY" --data-urlencode "name=$PROJECT_NAME" > /dev/null
  log "project $PROJECT_KEY created"
fi

# --- metrics: Multi-Quality Rule mode (default since SonarQube 2025.1) or Standard Experience ---------------
mqr=$(api GET settings/values --data-urlencode "keys=sonar.multi-quality-mode.enabled" 2>/dev/null || true)
case "$mqr" in
  *'"value":"false"'*)
    MODE=standard
    M_MAINT=new_maintainability_rating; M_REL=new_reliability_rating; M_SEC=new_security_rating
    M_BLOCKER=new_blocker_violations; M_HIGH=new_critical_violations ;;
  *)
    MODE=mqr
    M_MAINT=new_software_quality_maintainability_rating; M_REL=new_software_quality_reliability_rating
    M_SEC=new_software_quality_security_rating
    M_BLOCKER=new_software_quality_blocker_issues; M_HIGH=new_software_quality_high_issues ;;
esac
log "metric set: $MODE"

# --- quality gate -----------------------------------------------------------------------------------------
if api GET qualitygates/show --data-urlencode "name=$GATE" > /dev/null 2>&1; then
  log "gate \"$GATE\" exists - replacing its conditions"
else
  api POST qualitygates/create --data-urlencode "name=$GATE" > /dev/null
  log "gate \"$GATE\" created"
fi
# Start from an empty gate: recent versions seed a new gate with the "Sonar way" conditions.
conditions=$(api GET qualitygates/show --data-urlencode "name=$GATE" | sed -n 's/.*"conditions":\[\([^]]*\)\].*/\1/p')
for id in $(printf '%s' "$conditions" | grep -o '"id":"[^"]*"' | sed 's/"id":"\([^"]*\)"/\1/'); do
  api POST qualitygates/delete_condition --data-urlencode "id=$id" > /dev/null
done

cond() { # metric op error
  api POST qualitygates/create_condition --data-urlencode "gateName=$GATE" \
    --data-urlencode "metric=$1" --data-urlencode "op=$2" --data-urlencode "error=$3" > /dev/null
  log "  condition $1 $2 $3"
}
# Clean as you code: every condition judges NEW code only; the existing code is the baseline.
cond new_coverage LT "$NEW_COVERAGE"
cond new_duplicated_lines_density GT "$NEW_DUPLICATION"
cond "$M_MAINT" GT "$RATING"
cond "$M_REL" GT "$RATING"
cond "$M_SEC" GT "$RATING"
cond new_security_hotspots_reviewed LT "$HOTSPOTS_REVIEWED"
if [ "$ZERO_NEW_ISSUES" = "true" ]; then
  cond new_violations GT 0
else
  cond "$M_BLOCKER" GT 0
  cond "$M_HIGH" GT 0
fi

api POST qualitygates/select --data-urlencode "gateName=$GATE" --data-urlencode "projectKey=$PROJECT_KEY" > /dev/null
log "gate \"$GATE\" assigned to $PROJECT_KEY"

# --- new code definition ------------------------------------------------------------------------------------
case "$NEW_CODE" in
  PREVIOUS_VERSION)
    api POST new_code_periods/set --data-urlencode "project=$PROJECT_KEY" --data-urlencode "type=PREVIOUS_VERSION" > /dev/null ;;
  NUMBER_OF_DAYS:*|REFERENCE_BRANCH:*)
    api POST new_code_periods/set --data-urlencode "project=$PROJECT_KEY" \
      --data-urlencode "type=${NEW_CODE%%:*}" --data-urlencode "value=${NEW_CODE#*:}" > /dev/null ;;
  *) echo "sonar-setup: unknown SONAR_NEW_CODE=$NEW_CODE" >&2; exit 2 ;;
esac
log "new code definition: $NEW_CODE"

# --- optional analysis token ---------------------------------------------------------------------------------
if [ "$CREATE_TOKEN" = "true" ]; then
  name="ci-$PROJECT_KEY-$(date +%Y%m%d%H%M%S)"
  token=$(api POST user_tokens/generate --data-urlencode "name=$name" \
    --data-urlencode "type=PROJECT_ANALYSIS_TOKEN" --data-urlencode "projectKey=$PROJECT_KEY" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  log "analysis token $name (shown once, store it as the masked CI variable SONAR_TOKEN):"
  echo "$token"
fi
log "done"
