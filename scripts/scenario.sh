#!/usr/bin/env bash
# Scenario controller CLI (box1-docs#19). Calls only this controller's API; never a shell, kubeconfig or other service.
# Needs: SCENARIO_URL (default: the dev Route; https only, except localhost) and TOKEN, a bearer token from client
# scenario-controller-cli carrying the scenario-operator (or scenario-viewer, for reads) role of client
# scenario-controller. The token is read from the environment, passed to curl through a file descriptor (never on its
# command line, so other users cannot see it in ps), and never printed.
set -euo pipefail

URL="${SCENARIO_URL:-https://scenario-controller-dev.apps.ocp.box1.org}"
: "${TOKEN:?Set TOKEN to a bearer token from client scenario-controller-cli}"
case "$URL" in
  https://*|http://localhost:*|http://127.0.0.1:*) ;;
  *) echo "SCENARIO_URL must use https" >&2; exit 2;;
esac

call() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS --fail-with-body -X "$method" -H 'Accept: application/json')
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  # printf is a shell builtin, so the token reaches curl through a pipe, not argv.
  curl "${args[@]}" -H @<(printf 'Authorization: Bearer %s\n' "$TOKEN") "${URL}${path}"
  echo
}

name_ok() { [[ "$1" =~ ^[a-z][a-z0-9-]{1,48}$ ]] || { echo "Scenario name is not valid" >&2; exit 2; }; }
run_ok() { [[ "$1" =~ ^scn_[0-9a-f]{20}$ ]] || { echo "Run ID is not valid" >&2; exit 2; }; }

case "${1:-help}" in
  list)     call GET /internal/v1/scenarios ;;
  fixtures) call GET /internal/v1/fixtures ;;
  seed)     call POST /internal/v1/fixtures/seed ;;
  start)    name_ok "${2:?scenario}"
            if [[ -n "${3:-}" ]]; then [[ "$3" =~ ^[0-9]{1,4}$ ]] || { echo "TTL must be seconds" >&2; exit 2; }
              call POST "/internal/v1/scenarios/$2/start" "{\"ttlSeconds\":$3}"
            else call POST "/internal/v1/scenarios/$2/start" '{}'; fi ;;
  stop)     name_ok "${2:?scenario}"; call POST "/internal/v1/scenarios/$2/stop" ;;
  reset)    name_ok "${2:?scenario}"; call POST "/internal/v1/scenarios/$2/reset" ;;
  run)      run_ok "${2:?run ID}"; call GET "/internal/v1/runs/$2" ;;
  runs)     call GET /internal/v1/runs ;;
  confirm)  run_ok "${2:?run ID}"; [[ "${3:-}" =~ ^[a-z][a-z0-9-]{1,40}$ ]] || { echo "Step is not valid" >&2; exit 2; }
            call POST "/internal/v1/runs/$2/confirm" "{\"step\":\"$3\"}" ;;
  audit)    call GET /internal/v1/audit ;;
  watch)    run_ok "${2:?run ID}"
            while true; do
              out="$(call GET "/internal/v1/runs/$2")"
              python3 -c 'import json,sys; r=json.loads(sys.stdin.read()); print(r["status"], "|", r.get("operatorStep") or r.get("detail") or "", "|", r.get("resetResult") or "")' <<<"$out"
              python3 -c 'import json,sys; sys.exit(0 if json.loads(sys.stdin.read()).get("resetResult") else 1)' <<<"$out" && break
              sleep 3
            done ;;
  *) cat <<'USAGE'
Usage: scripts/scenario.sh <command>
  list | fixtures | runs | audit
  seed                       create the fixture set (idempotent)
  start <scenario> [ttl]     start a named scenario
  stop <scenario> | reset <scenario>
  run <scenarioRunId> | watch <scenarioRunId>
  confirm <scenarioRunId> <step>   confirm an operator step the controller cannot observe
USAGE
  ;;
esac
