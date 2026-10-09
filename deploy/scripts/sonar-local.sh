#!/bin/sh
# Local SonarQube analysis of the whole repository (GR-Q3, docs/guides/code-quality.md): coverage of
# backend, frontend and Python, then one scan into the project `back-office` and a wait for the gate.
#
#   docker compose -f deploy/compose/docker-compose.yml --profile quality up -d sonarqube
#   SONAR_TOKEN=squ_... sh deploy/scripts/sonar-local.sh
#
# Environment:
#   SONAR_HOST_URL      [http://localhost:9100]
#   SONAR_TOKEN         analysis token (required; sonar-setup.sh SONAR_CREATE_TOKEN=true prints one)
#   SKIP_BACKEND=true   reuse backend/**/build/jacoco/*.exec of an earlier `gradlew test` (only re-aggregates)
#   SKIP_FRONTEND=true  reuse frontend/coverage/back-office-web/lcov.info
#   SKIP_PYTHON=true    reuse build/coverage/python-coverage.xml
#   GRADLE_ARGS         extra Gradle arguments (e.g. --offline)
# Windows: run from Git Bash with JAVA_HOME pointing at a JDK 21 (see CLAUDE.md).
# GRADLE_ARGS is split into words on purpose.
# shellcheck disable=SC2086
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HOST="${SONAR_HOST_URL:-http://localhost:9100}"
: "${SONAR_TOKEN:?set SONAR_TOKEN (analysis token), see docs/guides/code-quality.md}"
GRADLE_ARGS="${GRADLE_ARGS:-}"

case "$(curl -fsS "$HOST/api/system/status" 2>/dev/null || true)" in
  *'"status":"UP"'*) ;;
  *) echo "sonar-local: SonarQube at $HOST is not UP (docker compose ... --profile quality up -d sonarqube)" >&2; exit 1 ;;
esac

cd "$ROOT/backend"
if [ "${SKIP_BACKEND:-false}" = "true" ]; then
  echo "== backend: reusing existing JaCoCo execution data"
  ./gradlew --no-daemon $GRADLE_ARGS jacocoAggregatedReport
else
  echo "== backend: tests + JaCoCo (failed tests do not stop the scan, the report covers what ran)"
  ./gradlew --no-daemon --no-parallel --continue $GRADLE_ARGS test jacocoAggregatedReport || \
    echo "sonar-local: some backend tests failed - coverage is partial"
  ./gradlew --no-daemon $GRADLE_ARGS jacocoAggregatedReport
fi

if [ "${SKIP_FRONTEND:-false}" != "true" ]; then
  echo "== frontend: vitest with v8 coverage (lcov)"
  cd "$ROOT/frontend"
  [ -d node_modules ] || npm ci --no-audit --no-fund
  npm run test:ci || echo "sonar-local: some frontend tests failed - coverage is partial"
fi

if [ "${SKIP_PYTHON:-false}" != "true" ]; then
  echo "== python: runner/ and ci/ unit tests with coverage.py"
  cd "$ROOT"
  PY=$(command -v python3 || command -v python || true)
  if [ -n "$PY" ] && "$PY" -c "import coverage" 2>/dev/null; then
    mkdir -p build/coverage
    # settings in .coveragerc (relative paths, so the report matches on any checkout)
    "$PY" -m coverage run -m unittest discover -s runner/tests || echo "sonar-local: some Python tests failed"
    "$PY" -m coverage xml -o build/coverage/python-coverage.xml
  else
    echo "sonar-local: python coverage skipped (pip install coverage)"
  fi
fi

echo "== scan -> $HOST (waits for the quality gate)"
cd "$ROOT/backend"
# SONAR_TOKEN reaches the scanner through the environment, not the command line.
export SONAR_TOKEN
./gradlew --no-daemon $GRADLE_ARGS sonar -Dsonar.gradle.skipCompile=true \
  -Dsonar.host.url="$HOST" -Dsonar.qualitygate.wait=true -Dsonar.qualitygate.timeout=600
