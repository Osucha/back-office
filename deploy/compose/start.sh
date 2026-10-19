#!/bin/sh
# Back Office in Docker, one step (ADR 0046): database, object storage, api, worker, web and the runner fleet.
# The first start builds the images (several minutes).
set -e
cd "$(dirname "$0")"
docker info >/dev/null 2>&1 || { echo "Docker is not running." >&2; exit 1; }
docker compose -f docker-compose.yml --profile full up -d --build
echo "Waiting for Back Office on http://localhost:8080 ..."
tries=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/v1/auth/config)" = "200" ]; do
  tries=$((tries + 1))
  if [ "$tries" -ge 120 ]; then
    echo "No answer within 10 minutes; check: docker compose -f $(pwd)/docker-compose.yml logs api" >&2
    exit 1
  fi
  sleep 5
done
echo "Back Office runs on http://localhost:8080"
echo "Sign in with admin@example.com / ChangeMe-Local-123 (you set your own password at the first sign-in)."
