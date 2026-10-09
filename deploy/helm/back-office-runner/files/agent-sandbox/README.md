Vendored upstream manifest of kubernetes-sigs/agent-sandbox **v1.0.4** (CRDs agents.x-k8s.io/v1beta1
Sandbox, extensions.agents.x-k8s.io/v1beta1 SandboxTemplate/SandboxClaim/SandboxWarmPool, controller in
namespace agent-sandbox-system). Source:
https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v1.0.4/sandbox-with-extensions.yaml

Update: download the new release asset over this file, bump the annotation in Chart.yaml and
agentSandbox.version in values.yaml, run the helm lint variants of docs/guides/runner-fleet-install.md.
