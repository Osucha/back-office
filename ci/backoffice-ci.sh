#!/bin/sh
# backoffice-ci.sh - Back Office CI client (ADR 0013, docs/architecture/05-integrations.md).
#
# Served by the platform at GET /ci/backoffice-ci.sh and attached to releases. POSIX sh; needs curl
# and git. No jq: JSON is produced and parsed with awk (see the embedded program below).
#
# Environment
#   BACKOFFICE_URL              base URL of the platform (required)
#   BACKOFFICE_CI_TOKEN         project CI token, bo_ci_... (required)
#   BACKOFFICE_REVIEW_TIMEOUT   seconds to wait for a run with --wait (default 1800)
#   BACKOFFICE_POLL_INTERVAL    seconds between polls (default 10)
#   BACKOFFICE_ADVISORY         true -> never fail the job (overrides the entrypoint mode)
#   BACKOFFICE_DIFF_MAX_BYTES   cap for the uploaded diff (default 2097152; larger diffs are truncated)
#   BACKOFFICE_HTTP_TIMEOUT     per-request curl timeout in seconds (default 60)
#
# Commands
#   run <entrypoint> [--wait] [--diff auto|none] [--var k=v]...
#   review                      = run code-review --wait --diff auto
#   signal <name> --status success|fail [--run <id>] [--message "..."]
#   status <executionId>
#   version | help
#
# Exit codes: 0 gate PASS (or advisory), 1 gate FAIL, 2 transport/auth/usage error, 3 timeout.
# The token is never printed.

set -eu

BACKOFFICE_CI_VERSION="2.1.0"

# --------------------------------------------------------------------------------------------
# Temp files and cleanup
# --------------------------------------------------------------------------------------------

TMP=$(mktemp -d 2>/dev/null || mktemp -d -t backoffice-ci) || { echo "backoffice-ci: cannot create a temp directory" >&2; exit 2; }
trap 'rm -rf "$TMP"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
AWK_PROG="$TMP/bo.awk"
RESP="$TMP/response.json"
FLAT="$TMP/response.flat"
CURLCFG="$TMP/curl.cfg"
HTTP_CODE=000

# Embedded awk program (POSIX awk: works with gawk, mawk and busybox awk). Modes (-v mode=...):
#   string   file (raw text)       -> one quoted JSON string (no newline)
#   array    file (one item/line)  -> JSON array of strings
#   request  file (k=v lines)      -> {"entrypoint","context","variables"} without the closing brace
#   signal   -                     -> complete CiSignalRequest body
#   flatten  file (JSON document)  -> lines "path<TAB>type<TAB>rawvalue" (type s=string, l=literal)
#   get/has  flattened file        -> unescaped value of path -v p=... / exit 0 when present
#   print    flattened file        -> human readable execution report
# Context and request fields are read from ENVIRON (exported by the shell), which unlike -v does no
# backslash processing. Escaping never uses gsub replacement strings (their backslash handling
# differs between awks): strings are split on the character and re-joined by concatenation.
cat > "$AWK_PROG" <<"EOF"
function rep(s, re, with,   n, parts, out, i) {
  n = split(s, parts, re)
  if (n == 0) return ""
  out = parts[1]
  for (i = 2; i <= n; i++) out = out with parts[i]
  return out
}
function esc(s) {
  s = rep(s, "[\\\\]", bs bs)
  s = rep(s, "[\"]", bs "\"")
  s = rep(s, "[\t]", bs "t")
  s = rep(s, "[\r]", bs "r")
  s = rep(s, "[\n]", bs "n")
  return s
}
function q(s) { return "\"" esc(s) "\"" }
function qn(s) { return (s == "") ? "null" : q(s) }
function unescape(s,   out, i, n, c, d) {
  out = ""; n = length(s); i = 1
  while (i <= n) {
    c = substr(s, i, 1)
    if (c != bs) { out = out c; i++; continue }
    d = substr(s, i + 1, 1); i += 2
    if (d == "n") out = out "\n"
    else if (d == "t") out = out "\t"
    else if (d == "r") out = out "\r"
    else if (d == "b") out = out "\b"
    else if (d == "f") out = out "\f"
    else if (d == "u") { out = out bs "u" substr(s, i, 4); i += 4 }
    else out = out d
  }
  return out
}
function ctx(   e, s) {
  s = "{\"provider\":" q(ENVIRON["PROVIDER"]) ",\"projectPath\":" qn(ENVIRON["PROJECT_PATH"]) \
      ",\"pipelineId\":" qn(ENVIRON["PIPELINE_ID"]) ",\"jobId\":" qn(ENVIRON["JOB_ID"]) ",\"jobUrl\":" qn(ENVIRON["JOB_URL"]) \
      ",\"ref\":" qn(ENVIRON["REF"]) ",\"commitSha\":" qn(ENVIRON["COMMIT_SHA"]) ",\"defaultBranch\":" qn(ENVIRON["DEFAULT_BRANCH"]) \
      ",\"actor\":" qn(ENVIRON["ACTOR"]) ",\"repositoryUrl\":" qn(ENVIRON["REPOSITORY_URL"]) ",\"mergeRequest\":"
  if (ENVIRON["MR_IID"] != "")
    s = s "{\"iid\":" q(ENVIRON["MR_IID"]) ",\"title\":" qn(ENVIRON["MR_TITLE"]) ",\"description\":" qn(ENVIRON["MR_DESCRIPTION"]) \
        ",\"sourceBranch\":" qn(ENVIRON["MR_SOURCE"]) ",\"targetBranch\":" qn(ENVIRON["MR_TARGET"]) ",\"url\":" qn(ENVIRON["MR_URL"]) \
        ",\"author\":" qn(ENVIRON["MR_AUTHOR"]) "}"
  else s = s "null"
  return s "}"
}
function path(   p, k) { p = ""; for (k = 1; k <= depth; k++) p = p (k > 1 ? "." : "") key[k]; return p }
function emit(p, t, v) { printf "%s\t%s\t%s\n", p, t, v }
function val(p) { return (p in V) ? V[p] : "" }
function indent(s,   n, lines, i) { n = split(s, lines, "\n"); for (i = 1; i <= n; i++) print "  " lines[i] }
BEGIN {
  bs = sprintf("%c", 92)
  if (mode == "string") printf "\""
  if (mode == "array") printf "["
  if (mode == "request") printf "{\"entrypoint\":%s,\"context\":%s,\"variables\":{", q(ENVIRON["ENTRYPOINT"]), ctx()
  if (mode == "signal") {
    printf "{\"signal\":%s,\"status\":%s,\"runId\":%s,\"branch\":%s,\"commitSha\":%s,\"message\":%s,\"context\":%s}", \
      q(ENVIRON["SIGNAL"]), q(ENVIRON["SIG_STATUS"]), qn(ENVIRON["RUN_ID"]), qn(ENVIRON["SIG_BRANCH"]), qn(ENVIRON["COMMIT_SHA"]), qn(ENVIRON["MESSAGE"]), ctx()
    exit 0
  }
  consumed = 0; items = 0
}
mode == "string" {
  if (NR > 1) printf "%s", bs "n"
  printf "%s", esc($0)
  consumed += length($0) + 1
  next
}
mode == "array" {
  if ($0 == "") next
  if (items > 0) printf ","
  printf "%s", q($0)
  items++
  next
}
mode == "request" {
  if ($0 == "") next
  i = index($0, "=")
  k = (i > 0) ? substr($0, 1, i - 1) : $0
  v = (i > 0) ? substr($0, i + 1) : ""
  if (items > 0) printf ","
  printf "%s:%s", q(k), q(v)
  items++
  next
}
mode == "get" || mode == "has" {
  if (index($0, p "\t") == 1) {
    if (mode == "has") { found = 1; exit }
    t = substr($0, length(p) + 2, 1)
    v = substr($0, length(p) + 4)
    if (t == "l" && v == "null") exit
    if (t == "s") print unescape(v); else print v
    exit
  }
  next
}
mode == "print" {
  i = index($0, "\t")
  if (i == 0) next
  pth = substr($0, 1, i - 1); t = substr($0, i + 1, 1); v = substr($0, i + 3)
  if (t == "l" && v == "null") next
  V[pth] = (t == "s") ? unescape(v) : v
  next
}
mode == "flatten" { buf = buf $0 "\n"; next }
END {
  if (mode == "string") { if (total > 0 && consumed == total) printf "%s", bs "n"; printf "\""; exit 0 }
  if (mode == "array") { printf "]"; exit 0 }
  if (mode == "request") { printf "}"; exit 0 }
  if (mode == "has") exit (found ? 0 : 1)
  if (mode == "print") {
    base = ENVIRON["BASE_URL"]
    print ""
    print "================================ BACK OFFICE ================================"
    print "Execution: " (val("id") == "" ? "-" : val("id")) "  entrypoint: " val("entrypoint") "  project: " val("projectKey")
    print "Status:    " (val("status") == "" ? "-" : val("status"))
    print "Gate:      " (val("gateResult") == "" ? "-" : val("gateResult")) (val("mode") == "" ? "" : " (mode " val("mode") ")")
    if (val("report.verdict") != "") print "Verdict:   " val("report.verdict") (val("report.score") == "" ? "" : " (score " val("report.score") ")")
    if (val("cost.amount") != "") print "Cost:      " val("cost.amount") " " (val("cost.currency") == "" ? "USD" : val("cost.currency"))
    if (val("runId") != "") print "Run:       " base "/runs/" val("runId")
    if (val("error") != "") print "Error:     " val("error")
    summary = val("summary"); if (summary == "") summary = val("report.summary")
    if (summary != "") { print ""; print "Summary:"; indent(summary) }
    n = 0
    while (("report.findings." n ".severity") in V || ("report.findings." n ".title") in V) {
      if (n == 0) { print ""; print "Findings:" }
      sev = val("report.findings." n ".severity"); f = val("report.findings." n ".file"); l = val("report.findings." n ".line")
      ttl = val("report.findings." n ".title"); if (ttl == "") ttl = val("report.findings." n ".detail")
      print "  [" (sev == "" ? "INFO" : sev) "] " (f == "" ? "-" : f) (l == "" ? "" : ":" l) " - " (ttl == "" ? "-" : ttl)
      n++
    }
    if (n == 0) print "Findings:  none"
    print "============================================================================="
    exit 0
  }
  if (mode != "flatten") exit 0
  n = length(buf); depth = 0; i = 1
  while (i <= n) {
    c = substr(buf, i, 1)
    if (c == " " || c == "\t" || c == "\n" || c == "\r") { i++; continue }
    if (c == "{") { depth++; type[depth] = "o"; key[depth] = ""; expect[depth] = "k"; i++; continue }
    if (c == "[") { depth++; type[depth] = "a"; idx[depth] = 0; key[depth] = 0; i++; continue }
    if (c == "}" || c == "]") { if (depth > 0) depth--; i++; continue }
    if (c == ",") { if (type[depth] == "a") { idx[depth]++; key[depth] = idx[depth] } else expect[depth] = "k"; i++; continue }
    if (c == ":") { expect[depth] = "v"; i++; continue }
    if (c == "\"") {
      j = i + 1; s = ""
      while (j <= n) {
        d = substr(buf, j, 1)
        if (d == bs) { s = s d substr(buf, j + 1, 1); j += 2; continue }
        if (d == "\"") break
        s = s d; j++
      }
      i = j + 1
      if (depth > 0 && type[depth] == "o" && expect[depth] == "k") { key[depth] = s; continue }
      emit(path(), "s", s)
      continue
    }
    j = i
    while (j <= n) {
      d = substr(buf, j, 1)
      if (d == "," || d == "}" || d == "]" || d == " " || d == "\n" || d == "\r" || d == "\t") break
      j++
    }
    emit(path(), "l", substr(buf, i, j - i))
    i = j
  }
}
EOF

# --------------------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------------------

log() { printf "%s\n" "$*"; }
err() { printf "backoffice-ci: %s\n" "$*" >&2; }
die() { err "$1"; exit "${2:-2}"; }
lower() { printf "%s" "$1" | tr "[:upper:]" "[:lower:]"; }
# BINMODE=3 keeps CR bytes intact with gawk builds for Windows (Git Bash); other awks ignore it.
bo_awk() { LC_ALL=C awk -v BINMODE=3 -f "$AWK_PROG" "$@"; }

# json_string_file FILE -> quoted JSON string of the file contents (control chars except TAB/LF/CR dropped)
json_string_file() {
  LC_ALL=C tr -d "\000-\010\013\014\016-\037\177" < "$1" > "$TMP/esc.in"
  bo_awk -v mode=string -v total="$(wc -c < "$TMP/esc.in" | tr -d " ")" "$TMP/esc.in"
}
# json_array_file FILE (one item per line) -> JSON array of strings
json_array_file() {
  LC_ALL=C tr -d "\000-\010\013\014\016-\037\177" < "$1" > "$TMP/arr.in"
  bo_awk -v mode=array "$TMP/arr.in"
}
# flatten_response -> $FLAT from $RESP
flatten_response() { bo_awk -v mode=flatten "$RESP" > "$FLAT" 2>/dev/null || : > "$FLAT"; }
# jget PATH [FLATFILE] -> value (unescaped), empty for null/missing
jget() { bo_awk -v mode=get -v p="$1" "${2:-$FLAT}"; }
jhas() { bo_awk -v mode=has -v p="$1" "${2:-$FLAT}"; }

git_q() { git "$@" 2>/dev/null; }
have_git_repo() { git rev-parse --is-inside-work-tree >/dev/null 2>&1; }

# --------------------------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------------------------

require_config() {
  [ -n "${BACKOFFICE_URL:-}" ] || die "BACKOFFICE_URL is required (base URL of Back Office)"
  # Most common cause in GitLab: the variable is marked Protected, so pipelines of unprotected (feature) branches,
  # including their merge request pipelines, get it empty.
  [ -n "${BACKOFFICE_CI_TOKEN:-}" ] || die "BACKOFFICE_CI_TOKEN is required but empty or not set (project CI token bo_ci_..., issued in Project > CI). GitLab: a variable marked Protected is not passed to pipelines of unprotected branches (merge request pipelines of feature branches) - mark it Masked, not Protected"
  BASE_URL=${BACKOFFICE_URL%/}
  export BASE_URL
  API="$BASE_URL/api/v1"
  TIMEOUT=${BACKOFFICE_REVIEW_TIMEOUT:-1800}
  POLL=${BACKOFFICE_POLL_INTERVAL:-10}
  HTTP_TIMEOUT=${BACKOFFICE_HTTP_TIMEOUT:-60}
  DIFF_MAX=${BACKOFFICE_DIFF_MAX_BYTES:-2097152}
  ADVISORY=$(lower "${BACKOFFICE_ADVISORY:-false}")
  case "$ADVISORY" in 1|yes|y|on) ADVISORY=true ;; true) ;; *) ADVISORY=false ;; esac
  # The token goes into a curl config file (mode 0600) so it never appears in process listings.
  _um=$(umask)
  umask 077
  {
    printf "header = \"Authorization: Bearer %s\"\n" "$BACKOFFICE_CI_TOKEN"
    printf "header = \"Accept: application/json\"\n"
    printf "header = \"User-Agent: backoffice-ci/%s\"\n" "$BACKOFFICE_CI_VERSION"
  } > "$CURLCFG"
  umask "$_um"
}

# api_call METHOD URL [BODYFILE] -> $RESP, $HTTP_CODE ; returns 1 on transport failure
api_call() {
  _method=$1; _url=$2; _body=${3:-}
  : > "$RESP"
  if [ -n "$_body" ]; then
    HTTP_CODE=$(curl -sS --max-time "$HTTP_TIMEOUT" --config "$CURLCFG" -o "$RESP" -w "%{http_code}" \
      -X "$_method" -H "Content-Type: application/json" --data-binary @- "$_url" < "$_body") || { HTTP_CODE=000; return 1; }
  else
    HTTP_CODE=$(curl -sS --max-time "$HTTP_TIMEOUT" --config "$CURLCFG" -o "$RESP" -w "%{http_code}" \
      -X "$_method" "$_url") || { HTTP_CODE=000; return 1; }
  fi
  return 0
}

# Explain a failed call (never prints the token) and exit 2 (or 0 in advisory mode).
fail_http() {
  _what=$1
  case "$HTTP_CODE" in
    000) err "$_what: cannot reach $BASE_URL (transport error)" ;;
    401) err "$_what: HTTP 401 - BACKOFFICE_CI_TOKEN is missing, revoked or not a project CI token" ;;
    403) err "$_what: HTTP 403 - the token does not belong to the project that owns this entrypoint/run" ;;
    404) err "$_what: HTTP 404 - unknown entrypoint, execution or URL (check BACKOFFICE_URL)" ;;
    *)   err "$_what: HTTP $HTTP_CODE" ;;
  esac
  if [ -s "$RESP" ]; then
    flatten_response
    _detail=$(jget detail); [ -n "$_detail" ] || _detail=$(jget message); [ -n "$_detail" ] || _detail=$(jget error)
    if [ -n "$_detail" ]; then err "  $_detail"; else head -c 1000 "$RESP" >&2; echo >&2; fi
  fi
  if [ "$ADVISORY" = true ]; then log "(advisory mode: not failing the job)"; exit 0; fi
  exit 2
}

# --------------------------------------------------------------------------------------------
# CI context detection (GitLab CI, GitHub Actions, other). Exported for the awk program.
# --------------------------------------------------------------------------------------------

detect_context() {
  PROVIDER=OTHER; PROJECT_PATH=""; PIPELINE_ID=""; JOB_ID=""; JOB_URL=""; REF=""; COMMIT_SHA=""; DEFAULT_BRANCH=""; ACTOR=""; REPOSITORY_URL=""
  MR_IID=""; MR_TITLE=""; MR_DESCRIPTION=""; MR_SOURCE=""; MR_TARGET=""; MR_URL=""; MR_AUTHOR=""; MR_DIFF_BASE_SHA=""
  if [ -n "${GITLAB_CI:-}" ] || [ -n "${CI_PROJECT_PATH:-}" ]; then
    PROVIDER=GITLAB
    PROJECT_PATH=${CI_PROJECT_PATH:-}
    REPOSITORY_URL=${CI_PROJECT_URL:-}
    PIPELINE_ID=${CI_PIPELINE_ID:-}
    JOB_ID=${CI_JOB_ID:-}
    JOB_URL=${CI_JOB_URL:-}
    REF=${CI_COMMIT_REF_NAME:-}
    COMMIT_SHA=${CI_COMMIT_SHA:-}
    DEFAULT_BRANCH=${CI_DEFAULT_BRANCH:-}
    ACTOR=${GITLAB_USER_LOGIN:-}
    if [ -n "${CI_MERGE_REQUEST_IID:-}" ]; then
      MR_IID=$CI_MERGE_REQUEST_IID
      MR_TITLE=${CI_MERGE_REQUEST_TITLE:-}
      MR_DESCRIPTION=${CI_MERGE_REQUEST_DESCRIPTION:-}
      MR_SOURCE=${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME:-}
      MR_TARGET=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME:-}
      MR_URL=${CI_MERGE_REQUEST_PROJECT_URL:-${CI_PROJECT_URL:-}}
      [ -n "$MR_URL" ] && MR_URL="$MR_URL/-/merge_requests/$MR_IID"
      MR_AUTHOR=${GITLAB_USER_LOGIN:-}
      MR_DIFF_BASE_SHA=${CI_MERGE_REQUEST_DIFF_BASE_SHA:-}
      [ -n "$REF" ] || REF=$MR_SOURCE
    fi
  elif [ -n "${GITHUB_ACTIONS:-}" ] || [ -n "${GITHUB_REPOSITORY:-}" ]; then
    PROVIDER=GITHUB
    PROJECT_PATH=${GITHUB_REPOSITORY:-}
    PIPELINE_ID=${GITHUB_RUN_ID:-}
    JOB_ID=${GITHUB_JOB:-}
    _server=${GITHUB_SERVER_URL:-https://github.com}
    [ -n "$PROJECT_PATH" ] && REPOSITORY_URL="$_server/$PROJECT_PATH"
    [ -n "$PIPELINE_ID" ] && JOB_URL="$_server/$PROJECT_PATH/actions/runs/$PIPELINE_ID"
    REF=${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-}}
    COMMIT_SHA=${GITHUB_SHA:-}
    ACTOR=${GITHUB_ACTOR:-}
    case "${GITHUB_REF:-}" in
      refs/pull/*/merge|refs/pull/*/head) MR_IID=${GITHUB_REF#refs/pull/}; MR_IID=${MR_IID%%/*} ;;
    esac
    if [ -n "${GITHUB_BASE_REF:-}" ]; then
      MR_SOURCE=${GITHUB_HEAD_REF:-}
      MR_TARGET=$GITHUB_BASE_REF
    fi
    # The event payload has what the environment lacks (title, body, author, default branch).
    if [ -n "${GITHUB_EVENT_PATH:-}" ] && [ -r "$GITHUB_EVENT_PATH" ]; then
      bo_awk -v mode=flatten "$GITHUB_EVENT_PATH" > "$TMP/event.flat" 2>/dev/null || : > "$TMP/event.flat"
      DEFAULT_BRANCH=$(jget repository.default_branch "$TMP/event.flat")
      _n=$(jget pull_request.number "$TMP/event.flat")
      if [ -n "$_n" ]; then
        MR_IID=$_n
        MR_TITLE=$(jget pull_request.title "$TMP/event.flat")
        MR_DESCRIPTION=$(jget pull_request.body "$TMP/event.flat")
        MR_URL=$(jget pull_request.html_url "$TMP/event.flat")
        MR_AUTHOR=$(jget pull_request.user.login "$TMP/event.flat")
        [ -n "$MR_SOURCE" ] || MR_SOURCE=$(jget pull_request.head.ref "$TMP/event.flat")
        [ -n "$MR_TARGET" ] || MR_TARGET=$(jget pull_request.base.ref "$TMP/event.flat")
        MR_DIFF_BASE_SHA=$(jget pull_request.base.sha "$TMP/event.flat")
      fi
    fi
    if [ -n "$MR_IID" ] && [ -z "$MR_URL" ]; then MR_URL="$_server/$PROJECT_PATH/pull/$MR_IID"; fi
    [ -n "$MR_AUTHOR" ] || MR_AUTHOR=$ACTOR
  fi
  if have_git_repo; then
    [ -n "$COMMIT_SHA" ] || COMMIT_SHA=$(git_q rev-parse HEAD || true)
    [ -n "$REF" ] || REF=$(git_q rev-parse --abbrev-ref HEAD || true)
    [ "$REF" = HEAD ] && REF=""
    if [ -z "$DEFAULT_BRANCH" ]; then
      DEFAULT_BRANCH=$(git_q symbolic-ref -q --short refs/remotes/origin/HEAD || true)
      DEFAULT_BRANCH=${DEFAULT_BRANCH#origin/}
    fi
    # Other CI systems: the origin remote, without credentials (user:token@ is never sent)
    if [ -z "$REPOSITORY_URL" ]; then
      REPOSITORY_URL=$(git_q remote get-url origin || true)
      REPOSITORY_URL=$(printf "%s" "$REPOSITORY_URL" | sed -e "s#^\([a-zA-Z][a-zA-Z0-9+.-]*://\)[^/@]*@#\1#")
    fi
  fi
  [ -n "$DEFAULT_BRANCH" ] || DEFAULT_BRANCH=main
  export PROVIDER PROJECT_PATH PIPELINE_ID JOB_ID JOB_URL REF COMMIT_SHA DEFAULT_BRANCH ACTOR REPOSITORY_URL
  export MR_IID MR_TITLE MR_DESCRIPTION MR_SOURCE MR_TARGET MR_URL MR_AUTHOR
}

describe_context() {
  if [ -n "$MR_IID" ]; then
    log "Context: $PROVIDER $PROJECT_PATH, merge request !$MR_IID ($MR_SOURCE -> $MR_TARGET) @ $(printf "%.12s" "$COMMIT_SHA")"
  else
    log "Context: $PROVIDER ${PROJECT_PATH:-<no project>}, ref ${REF:-?} @ $(printf "%.12s" "$COMMIT_SHA")"
  fi
}

# --------------------------------------------------------------------------------------------
# Diff collection
# --------------------------------------------------------------------------------------------

# try_diff DESCRIPTION ARGS... -> 0 when `git diff ARGS` succeeded (the diff may legitimately be empty);
# fills $DIFF_FILE, $FILES_FILE and DIFF_BASE_DESC.
try_diff() {
  _desc=$1; shift
  git diff "$@" > "$DIFF_FILE" 2>/dev/null || return 1
  git diff --name-only "$@" > "$FILES_FILE" 2>/dev/null || : > "$FILES_FILE"
  DIFF_BASE_DESC=$_desc
  return 0
}

# collect_diff -> $DIFF_FILE (raw diff, possibly truncated), $FILES_FILE (changed paths), DIFF_BASE_DESC
collect_diff() {
  DIFF_FILE="$TMP/diff.raw"; FILES_FILE="$TMP/files.txt"; DIFF_BASE_DESC=""
  : > "$DIFF_FILE"; : > "$FILES_FILE"
  have_git_repo || { err "not inside a git repository: no diff will be sent"; return 0; }
  if [ -n "$MR_TARGET" ]; then
    git_q fetch --no-tags --quiet origin "$MR_TARGET" >/dev/null || true
    try_diff "merge-base(origin/$MR_TARGET, HEAD)" --merge-base "origin/$MR_TARGET" HEAD \
      || try_diff "origin/$MR_TARGET...HEAD" "origin/$MR_TARGET...HEAD" \
      || { [ -n "$MR_DIFF_BASE_SHA" ] && try_diff "$MR_DIFF_BASE_SHA..HEAD" "$MR_DIFF_BASE_SHA" HEAD; } \
      || try_diff "origin/$MR_TARGET..HEAD" "origin/$MR_TARGET" HEAD \
      || err "cannot compute the merge request diff against $MR_TARGET (fetch failed or shallow clone?)"
  elif [ "$REF" != "$DEFAULT_BRANCH" ]; then
    git_q fetch --no-tags --quiet origin "$DEFAULT_BRANCH" >/dev/null || true
    try_diff "merge-base(origin/$DEFAULT_BRANCH, HEAD)" --merge-base "origin/$DEFAULT_BRANCH" HEAD \
      || try_diff "origin/$DEFAULT_BRANCH...HEAD" "origin/$DEFAULT_BRANCH...HEAD" \
      || try_diff "HEAD~1..HEAD (last commit; origin/$DEFAULT_BRANCH unavailable)" HEAD~1 HEAD \
      || err "cannot compute a diff for $REF"
  else
    try_diff "HEAD~1..HEAD (last commit on $DEFAULT_BRANCH)" HEAD~1 HEAD || err "cannot compute the last commit diff"
  fi
  if [ ! -s "$DIFF_FILE" ]; then
    log "Diff: empty (nothing to compare${DIFF_BASE_DESC:+ for $DIFF_BASE_DESC})"
    : > "$FILES_FILE"
    return 0
  fi
  _size=$(wc -c < "$DIFF_FILE" | tr -d " ")
  if [ "$_size" -gt "$DIFF_MAX" ]; then
    # Cut at the byte cap, drop the (possibly partial) last line so the text stays valid UTF-8.
    head -c "$DIFF_MAX" "$DIFF_FILE" | sed "\$d" > "$DIFF_FILE.cut"
    printf "\n[... diff truncated by backoffice-ci: %s of %s bytes sent (BACKOFFICE_DIFF_MAX_BYTES=%s) ...]\n" "$DIFF_MAX" "$_size" "$DIFF_MAX" >> "$DIFF_FILE.cut"
    mv "$DIFF_FILE.cut" "$DIFF_FILE"
    log "Diff: $DIFF_BASE_DESC, $_size bytes (truncated to $DIFF_MAX), $(wc -l < "$FILES_FILE" | tr -d " ") file(s)"
  else
    log "Diff: $DIFF_BASE_DESC, $_size bytes, $(wc -l < "$FILES_FILE" | tr -d " ") file(s)"
  fi
}

# --------------------------------------------------------------------------------------------
# Output of an execution
# --------------------------------------------------------------------------------------------

print_execution() {
  flatten_response
  bo_awk -v mode=print "$FLAT"
}

# decide exit code from the flattened terminal execution
gate_exit() {
  _status=$(jget status); _gate=$(jget gateResult); _mode=$(jget mode)
  if [ "$_gate" = PASS ]; then log "Gate PASS"; return 0; fi
  if [ "$ADVISORY" = true ]; then log "Gate ${_gate:-$_status}: advisory mode (BACKOFFICE_ADVISORY), not failing the job"; return 0; fi
  if [ "$_mode" = ADVISORY ]; then log "Gate ${_gate:-$_status}: entrypoint mode is ADVISORY, not failing the job"; return 0; fi
  if [ "$_gate" = FAIL ]; then log "Gate FAIL"; return 1; fi
  if [ "$_status" = FAILED ]; then log "Execution FAILED without a gate result"; return 1; fi
  log "Gate ${_gate:-PENDING}: execution finished without a decision"; return 1
}

# --------------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------------

cmd_run() {
  [ $# -ge 1 ] || die "usage: backoffice-ci run <entrypoint> [--wait] [--diff auto|none] [--var k=v]..."
  ENTRYPOINT=$1; shift
  export ENTRYPOINT
  WAIT=false; DIFF_MODE=auto; VARS_FILE="$TMP/vars.txt"; : > "$VARS_FILE"
  while [ $# -gt 0 ]; do
    case "$1" in
      --wait) WAIT=true ;;
      --no-wait) WAIT=false ;;
      --diff) shift; DIFF_MODE=${1:-auto} ;;
      --diff=*) DIFF_MODE=${1#--diff=} ;;
      --var) shift; printf "%s\n" "${1:-}" >> "$VARS_FILE" ;;
      --var=*) printf "%s\n" "${1#--var=}" >> "$VARS_FILE" ;;
      --timeout) shift; TIMEOUT=${1:-$TIMEOUT} ;;
      *) die "unknown option for run: $1" ;;
    esac
    shift
  done
  case "$DIFF_MODE" in auto|none) ;; *) die "--diff must be auto or none" ;; esac
  detect_context
  describe_context
  DIFF_FILE=""
  [ "$DIFF_MODE" = auto ] && collect_diff
  BODY="$TMP/body.json"
  {
    bo_awk -v mode=request "$VARS_FILE"
    if [ -n "$DIFF_FILE" ] && [ -s "$DIFF_FILE" ]; then
      printf ",\"diff\":"; json_string_file "$DIFF_FILE"
      printf ",\"changedFiles\":"; json_array_file "$FILES_FILE"
    else
      printf ",\"diff\":null,\"changedFiles\":[]"
    fi
    printf "}"
  } > "$BODY"
  log "Starting entrypoint \"$ENTRYPOINT\" at $API/ci/executions"
  api_call POST "$API/ci/executions" "$BODY" || fail_http "start execution"
  case "$HTTP_CODE" in 2*) ;; *) fail_http "start execution" ;; esac
  flatten_response
  EXEC_ID=$(jget id)
  [ -n "$EXEC_ID" ] || { err "unexpected response (HTTP $HTTP_CODE): $(head -c 500 "$RESP")"; [ "$ADVISORY" = true ] && exit 0; exit 2; }
  _status=$(jget status); _run=$(jget runId)
  log "Execution $EXEC_ID status=$_status${_run:+ run=$BASE_URL/runs/$_run} (HTTP $HTTP_CODE)"
  if [ "$WAIT" != true ]; then
    log "Not waiting (--wait not given). Poll with: backoffice-ci status $EXEC_ID"
    return 0
  fi
  wait_for_execution "$EXEC_ID"
}

wait_for_execution() {
  _id=$1
  _deadline=$(( $(date +%s) + TIMEOUT ))
  _status=$(jget status)
  _last=$_status
  log "Waiting up to ${TIMEOUT}s (poll every ${POLL}s)..."
  while [ "$_status" != SUCCEEDED ] && [ "$_status" != FAILED ]; do
    sleep "$POLL"
    if api_call GET "$API/ci/executions/$_id"; then
      case "$HTTP_CODE" in
        2*) flatten_response; _status=$(jget status) ;;
        401|403|404) fail_http "poll execution" ;;
        *) err "poll: HTTP $HTTP_CODE, retrying" ;;
      esac
    else
      err "poll: transport error, retrying"
    fi
    if [ "$_status" != "$_last" ]; then log "$(date -u +%H:%M:%S) status=$_status"; _last=$_status; fi
    if [ "$_status" != SUCCEEDED ] && [ "$_status" != FAILED ] && [ "$(date +%s)" -ge "$_deadline" ]; then
      err "timed out after ${TIMEOUT}s waiting for execution $_id (last status ${_status:-unknown})"
      _run=$(jget runId); [ -n "$_run" ] && err "  run: $BASE_URL/runs/$_run"
      if [ "$ADVISORY" = true ]; then log "(advisory mode: not failing the job)"; exit 0; fi
      exit 3
    fi
  done
  print_execution
  if gate_exit; then exit 0; else exit 1; fi
}

cmd_review() { cmd_run code-review --wait --diff auto "$@"; }

cmd_status() {
  [ $# -ge 1 ] || die "usage: backoffice-ci status <executionId>"
  api_call GET "$API/ci/executions/$1" || fail_http "get execution"
  case "$HTTP_CODE" in 2*) ;; *) fail_http "get execution" ;; esac
  print_execution
  return 0
}

cmd_signal() {
  [ $# -ge 1 ] || die "usage: backoffice-ci signal <name> --status success|fail [--run <id>] [--message \"...\"]"
  SIGNAL=$1; shift
  SIG_STATUS=""; RUN_ID=""; MESSAGE=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --status) shift; SIG_STATUS=${1:-} ;;
      --status=*) SIG_STATUS=${1#--status=} ;;
      --run) shift; RUN_ID=${1:-} ;;
      --run=*) RUN_ID=${1#--run=} ;;
      --message) shift; MESSAGE=${1:-} ;;
      --message=*) MESSAGE=${1#--message=} ;;
      *) die "unknown option for signal: $1" ;;
    esac
    shift
  done
  case "$(lower "$SIG_STATUS")" in
    success|succeeded|ok|pass|passed|0) SIG_STATUS=SUCCESS ;;
    fail|failed|failure|error|1) SIG_STATUS=FAIL ;;
    *) die "--status must be success or fail" ;;
  esac
  detect_context
  if [ -z "$RUN_ID" ] && have_git_repo; then
    RUN_ID=$(git_q log -1 --format="%(trailers:key=Back-Office-Run,valueonly)" | head -n1 | tr -d " \r")
    [ -n "$RUN_ID" ] && log "Run id from Back-Office-Run trailer of HEAD: $RUN_ID"
  fi
  SIG_BRANCH=${MR_SOURCE:-$REF}
  [ -n "$RUN_ID" ] || log "No run id (no --run and no Back-Office-Run trailer on HEAD); the run is matched by branch ${SIG_BRANCH:-?} / commit $(printf "%.12s" "$COMMIT_SHA")"
  export SIGNAL SIG_STATUS RUN_ID MESSAGE SIG_BRANCH
  BODY="$TMP/signal.json"
  bo_awk -v mode=signal /dev/null > "$BODY"
  log "Sending signal \"$SIGNAL\" status=$SIG_STATUS to $API/ci/signals"
  api_call POST "$API/ci/signals" "$BODY" || fail_http "send signal"
  case "$HTTP_CODE" in
    2*) ;;
    404) flatten_response; log "No waiting run for this signal (HTTP 404): $(jget detail)"; return 0 ;;
    *) fail_http "send signal" ;;
  esac
  flatten_response
  _resumed=$(jget resumed); _run=$(jget runId); _msg=$(jget message)
  log "Result: ${_msg:-resumed=$_resumed}${_run:+ (run $BASE_URL/runs/$_run)}"
  return 0
}

usage() {
  cat <<EOF
backoffice-ci $BACKOFFICE_CI_VERSION - Back Office CI client

Usage:
  backoffice-ci run <entrypoint> [--wait] [--diff auto|none] [--var k=v]...
  backoffice-ci review                       (= run code-review --wait --diff auto)
  backoffice-ci signal <name> --status success|fail [--run <id>] [--message "..."]
  backoffice-ci status <executionId>
  backoffice-ci version | help

Environment: BACKOFFICE_URL, BACKOFFICE_CI_TOKEN (required); BACKOFFICE_REVIEW_TIMEOUT (1800),
  BACKOFFICE_POLL_INTERVAL (10), BACKOFFICE_ADVISORY (false), BACKOFFICE_DIFF_MAX_BYTES (2097152).
Exit codes: 0 gate PASS or advisory, 1 gate FAIL, 2 transport/auth/usage error, 3 timeout.
EOF
}

main() {
  [ $# -ge 1 ] || { usage; exit 2; }
  _cmd=$1; shift
  case "$_cmd" in
    version|--version|-v) log "backoffice-ci $BACKOFFICE_CI_VERSION"; exit 0 ;;
    help|--help|-h) usage; exit 0 ;;
  esac
  require_config
  case "$_cmd" in
    run) cmd_run "$@" ;;
    review) cmd_review "$@" ;;
    signal) cmd_signal "$@" ;;
    status) cmd_status "$@" ;;
    *) err "unknown command: $_cmd"; usage >&2; exit 2 ;;
  esac
}

main "$@"
