#!/usr/bin/env bash
# Checks the prerequisites of the Back Office runner fleet (docs/guides/runner-fleet-install.md) in the
# cluster of the current kubectl context:
#   1. Kubernetes version and permissions to install the chart
#   2. Agent Sandbox CRDs and controller (AGENT_SANDBOX backend) - else the K8S_JOB fallback is used
#   3. RuntimeClasses for STANDARD (gVisor) and HIGH (Kata) isolation, and a smoke pod on each
#   4. NetworkPolicy enforcement (a default-deny policy really blocks traffic)
#   5. KEDA (optional autoscaling) and outbound HTTPS to Back Office
#
# Usage: deploy/scripts/check-runner-cluster.sh [--standard gvisor] [--high kata] [--url https://bo.example.com]
#        [--namespace bo-runner-check] [--skip-pods] [--keep]
# Exit code: 0 all required checks pass, 1 a required check failed. Warnings do not fail.
set -uo pipefail

STANDARD=gvisor
HIGH=kata
URL=""
NS=bo-runner-check
SKIP_PODS=false
KEEP=false
IMAGE=busybox:1.37

while [ $# -gt 0 ]; do
  case "$1" in
    --standard) STANDARD="$2"; shift 2 ;;
    --high) HIGH="$2"; shift 2 ;;
    --url) URL="$2"; shift 2 ;;
    --namespace) NS="$2"; shift 2 ;;
    --skip-pods) SKIP_PODS=true; shift ;;
    --keep) KEEP=true; shift ;;
    --image) IMAGE="$2"; shift 2 ;;
    -h|--help) sed -n "2,13p" "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

FAIL=0
ok()   { printf "  [ OK ] %s\n" "$*"; }
warn() { printf "  [WARN] %s\n" "$*"; }
bad()  { printf "  [FAIL] %s\n" "$*"; FAIL=1; }

command -v kubectl > /dev/null || { echo "kubectl not found"; exit 1; }

echo "== Cluster"
if ! kubectl version > /dev/null 2>&1; then bad "cannot reach the cluster (kubectl context: $(kubectl config current-context 2>/dev/null))"; exit 1; fi
MINOR="$(kubectl version -o json 2>/dev/null | sed -n "s/.*\"minor\": *\"\([0-9]*\).*/\1/p" | tail -1)"
if [ -n "$MINOR" ] && [ "$MINOR" -ge 29 ]; then ok "Kubernetes 1.$MINOR"; else warn "Kubernetes 1.${MINOR:-?}: the chart needs >= 1.29 (native sidecars)"; fi
for r in "create namespaces" "create roles" "create networkpolicies" "create deployments"; do
  if kubectl auth can-i $r -A > /dev/null 2>&1; then ok "can $r"; else warn "cannot $r (needed to install the chart)"; fi
done

echo "== Agent Sandbox (AGENT_SANDBOX backend)"
CRDS_OK=true
for crd in sandboxes.agents.x-k8s.io sandboxtemplates.extensions.agents.x-k8s.io sandboxclaims.extensions.agents.x-k8s.io sandboxwarmpools.extensions.agents.x-k8s.io; do
  if kubectl get crd "$crd" > /dev/null 2>&1; then ok "CRD $crd"; else CRDS_OK=false; warn "CRD $crd missing"; fi
done
if [ "$CRDS_OK" = true ]; then
  if kubectl -n agent-sandbox-system get deploy agent-sandbox-controller > /dev/null 2>&1; then
    READY="$(kubectl -n agent-sandbox-system get deploy agent-sandbox-controller -o jsonpath="{.status.readyReplicas}")"
    if [ "${READY:-0}" -ge 1 ]; then ok "agent-sandbox-controller ready"; else bad "agent-sandbox-controller not ready"; fi
  else
    warn "CRDs present but no controller in agent-sandbox-system (installed elsewhere?)"
  fi
else
  warn "no Agent Sandbox: the controller falls back to K8S_JOB (no warm pool). Install with agentSandbox.install=true"
fi

echo "== RuntimeClasses"
check_rc() {
  local level="$1" rc="$2"
  if [ -z "$rc" ]; then warn "$level: no RuntimeClass configured (runc - development only)"; return; fi
  if kubectl get runtimeclass "$rc" > /dev/null 2>&1; then
    ok "$level: RuntimeClass $rc (handler $(kubectl get runtimeclass "$rc" -o jsonpath="{.handler}"))"
    echo "$rc" >> "$RC_FILE"
  else
    if [ "$level" = STANDARD ]; then bad "$level: RuntimeClass $rc missing"; else warn "$level: RuntimeClass $rc missing (isolation HIGH jobs cannot run)"; fi
  fi
}
RC_FILE="$(mktemp)"
trap "rm -f \"$RC_FILE\"" EXIT
check_rc STANDARD "$STANDARD"
[ "$HIGH" != "$STANDARD" ] && check_rc HIGH "$HIGH"

cleanup() { [ "$KEEP" = true ] || kubectl delete namespace "$NS" --wait=false > /dev/null 2>&1; }

if [ "$SKIP_PODS" = false ]; then
  kubectl create namespace "$NS" > /dev/null 2>&1 || true
  kubectl label namespace "$NS" pod-security.kubernetes.io/enforce=restricted --overwrite > /dev/null

  echo "== Sandbox pods per RuntimeClass"
  while read -r rc; do
    [ -z "$rc" ] && continue
    kubectl -n "$NS" delete pod "rc-$rc" --ignore-not-found > /dev/null 2>&1
    cat <<EOF | kubectl -n "$NS" apply -f - > /dev/null
apiVersion: v1
kind: Pod
metadata:
  name: rc-$rc
spec:
  runtimeClassName: $rc
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: { runAsNonRoot: true, runAsUser: 65532, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: probe
      image: $IMAGE
      command: ["sh", "-c", "dmesg 2>/dev/null | head -1; uname -a"]
      securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ["ALL"] } }
      resources: { requests: { cpu: 50m, memory: 64Mi }, limits: { memory: 256Mi } }
EOF
    if kubectl -n "$NS" wait --for=jsonpath="{.status.phase}"=Succeeded "pod/rc-$rc" --timeout=180s > /dev/null 2>&1; then
      OUT="$(kubectl -n "$NS" logs "rc-$rc" 2>/dev/null | tr "\n" " ")"
      case "$OUT" in
        *gVisor*|*"Starting gVisor"*) ok "$rc: pod ran inside gVisor ($OUT)" ;;
        *) ok "$rc: pod ran (kernel: $OUT)" ;;
      esac
    else
      bad "$rc: pod did not complete - $(kubectl -n "$NS" get pod "rc-$rc" -o jsonpath="{.status.phase} {.status.conditions[*].message}" 2>/dev/null)"
    fi
  done < "$RC_FILE"

  echo "== NetworkPolicy enforcement"
  kubectl -n "$NS" delete pod np-target np-client --ignore-not-found > /dev/null 2>&1
  kubectl -n "$NS" delete networkpolicy deny-all --ignore-not-found > /dev/null 2>&1
  kubectl -n "$NS" run np-target --image="$IMAGE" --labels=app=np-target --restart=Never \
    --overrides="{\"spec\":{\"securityContext\":{\"runAsNonRoot\":true,\"runAsUser\":65532,\"seccompProfile\":{\"type\":\"RuntimeDefault\"}},\"containers\":[{\"name\":\"np-target\",\"image\":\"$IMAGE\",\"command\":[\"sh\",\"-c\",\"mkdir -p /tmp/w && echo ok > /tmp/w/index.html && httpd -f -p 8080 -h /tmp/w\"],\"securityContext\":{\"allowPrivilegeEscalation\":false,\"capabilities\":{\"drop\":[\"ALL\"]}}}]}}" > /dev/null
  kubectl -n "$NS" wait --for=condition=Ready pod/np-target --timeout=120s > /dev/null 2>&1
  TARGET="$(kubectl -n "$NS" get pod np-target -o jsonpath="{.status.podIP}")"
  probe() {
    kubectl -n "$NS" delete pod np-client --ignore-not-found > /dev/null 2>&1
    kubectl -n "$NS" run np-client --image="$IMAGE" --restart=Never \
      --overrides="{\"spec\":{\"securityContext\":{\"runAsNonRoot\":true,\"runAsUser\":65532,\"seccompProfile\":{\"type\":\"RuntimeDefault\"}},\"containers\":[{\"name\":\"np-client\",\"image\":\"$IMAGE\",\"command\":[\"sh\",\"-c\",\"wget -q -T 5 -O - http://$TARGET:8080/ && echo REACHED || echo BLOCKED\"],\"securityContext\":{\"allowPrivilegeEscalation\":false,\"capabilities\":{\"drop\":[\"ALL\"]}}}]}}" > /dev/null
    kubectl -n "$NS" wait --for=jsonpath="{.status.phase}"=Succeeded pod/np-client --timeout=90s > /dev/null 2>&1
    kubectl -n "$NS" logs np-client 2>/dev/null | tail -1
  }
  BEFORE="$(probe)"
  cat <<EOF | kubectl -n "$NS" apply -f - > /dev/null
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-all
spec:
  podSelector: {}
  policyTypes: ["Ingress", "Egress"]
EOF
  sleep 5
  AFTER="$(probe)"
  if [ "$BEFORE" = REACHED ] && [ "$AFTER" = BLOCKED ]; then ok "NetworkPolicy is enforced (reached before, blocked after default-deny)"
  elif [ "$BEFORE" != REACHED ]; then warn "baseline connection failed ($BEFORE) - cannot judge enforcement"
  else bad "NetworkPolicy is NOT enforced: traffic passed a default-deny policy. Use a CNI that enforces it (Calico, Cilium, cloud policy engines)"; fi
  cleanup
fi

echo "== Optional"
if kubectl get crd scaledobjects.keda.sh > /dev/null 2>&1; then ok "KEDA installed (autoscaling.type=keda)"; else warn "KEDA not installed (autoscaling.type=none or hpa)"; fi
if [ -n "$URL" ]; then
  if kubectl run bo-url-check -n default --rm -i --restart=Never --image=curlimages/curl:8.16.0 --quiet -- \
      curl -fsS -o /dev/null -w "%{http_code}" "$URL/actuator/health/readiness" > /dev/null 2>&1; then
    ok "Back Office reachable from the cluster: $URL"
  else
    bad "Back Office not reachable from the cluster: $URL (outbound HTTPS, DNS, proxy, CA trust)"
  fi
fi

echo
if [ "$FAIL" = 0 ]; then echo "RESULT: prerequisites met"; else echo "RESULT: prerequisites missing (see FAIL lines)"; fi
exit "$FAIL"
