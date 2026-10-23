# My computer as a runner

Agent steps (Spec Kit, implementation, coding benchmarks) run in sandboxes of the [runner fleet](runner-fleet.md).
For your **private projects** ([My work](my-work.md#private-projects)) you can run them on **your own computer**
instead, the way a GitLab runner works: a small controller in Docker on your computer asks Back Office for work and
starts every step in its own sandbox container there. Optionally, Claude Code in those steps uses **your own Claude
login** instead of the Back Office LLM gateway.

<a id="personal-runner"></a>
## What it does

- You get one **personal runner pool**. It runs only the steps of **your private projects** in runs **you** started
  (also with **Start** in My work). Steps of team projects, and of anyone else, never go there.
- Once you have a personal runner, your private projects' steps **wait for it**: if your computer is off, they stay
  queued (up to the queue timeout, 24 h by default) and start when it comes back. Remove the personal runner to use
  the shared fleet again.
- You can connect several computers; any online one takes the next step.
- Administrators see your pool in **Administration → Runner fleet** (marked with an owner) and can revoke it.

<a id="connect"></a>
## Connect your computer

You need Docker (Docker Desktop on Windows and macOS) and the three images: `back-office/runner-controller`,
`back-office/runner` and `back-office/egress-proxy`. If your organisation does not publish them, build them from the
repository once:

```bash
docker build -t back-office/runner:dev runner/
docker build -t back-office/egress-proxy:dev backend/runner-controller/egress-proxy/
docker build -t back-office/runner-controller:dev -f backend/runner-controller/Dockerfile backend/
```

Then open **My account → My computer as a runner**:

1. Choose how Claude Code reaches the model (below).
2. Click **Connect this computer**. Back Office creates your pool and a **single-use registration token** (valid for
   24 hours, shown once) and builds a `docker run` command with it, for bash or PowerShell.
3. When you use your own Claude login (see below), put it into a variable in a terminal (it never leaves your
   computer and is not part of the command): `CLAUDE_CODE_OAUTH_TOKEN`. Git tokens are not set on the runner: the
   project's repository tokens reach each job encrypted to your runner's key
   ([Repository access tokens in the project](integrations-git.md#git-project-tokens)). A runner started earlier
   with `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH` still uses them for repositories without a project token.
4. Run the command in the same terminal. The controller registers, stores its runner token in
   `/var/lib/back-office-runner` (in Docker's file system) and restarts with Docker. The registration token is not
   needed again; to connect another computer, click **Connect this computer** again.

The computer appears under **Connected computers** with **online** within a minute. **Disconnect** revokes its token
(steps it is running go back to the queue); stop the container with `docker rm -f back-office-runner`.

When Back Office itself runs on the same computer (local testing), the command reaches it at
`host.docker.internal` and allows the egress proxy to connect to private addresses. The sandboxes call Back Office
back at `backoffice.runner.api-url` (`RUNNER_API_URL`); for a Back Office started with the `local` profile that is
`http://host.docker.internal:8080`.

<a id="credentials"></a>
## Claude Code with your own login

- **My own Claude login (subscription)**: Claude Code in the sandbox signs in with a token of your account and talks
  to Anthropic directly. Create the token once with `claude setup-token` (it opens the browser and prints a
  long-lived token) and put it into `CLAUDE_CODE_OAUTH_TOKEN` before starting the runner. Back Office does not meter
  these calls: the step's cost is **0** in runs, budgets and insights, while its
  [cost at API prices](costs-insights.md#api-cost) (Claude Code's estimate) is kept and shown next to it. Budgets and the gateway's model allow-list do not apply.
- **Back Office LLM gateway**: as on the shared fleet; your computer only provides the compute. Metered, budgets
  apply.

OpenCode steps always use the gateway. You can switch at any time; restart the runner with a new command when you
switch to your own login (the variable must be passed to the container).

The runner tells Back Office with every request for work which coding agents it has and whether your login is set
(ADR 0050). When it reports your login for the first time, you get the model profiles **Claude Opus (my login)**,
**Claude Sonnet (my login)** and **Claude Haiku (my login)**; **Account** → **My models** shows the runner's state
and the models you can use. A runner that is online without the login makes those models unusable (the model
pickers and the start of a run say so) instead of failing the job later.

<a id="corporate-proxy"></a>
## Behind a TLS-inspecting corporate proxy

Some company networks (Cisco Umbrella, Zscaler and similar) decrypt HTTPS on the way and re-sign it with their
own root certificate. Your computer trusts that root, the sandboxes do not: npm then fails with
`UNABLE_TO_GET_ISSUER_CERT_LOCALLY` and Java tools with `PKIX path building failed`. Agents never switch TLS
checks off to get round it; they stop and report it.

Give the runner the company's root certificate (a PEM file, ask your IT department or export it from the
browser): add two options to the `docker run` command, before the image name:

```
-v <path to the certificate on your computer>:/etc/back-office/extra-ca.pem:ro
-e RUNNER_EXTRA_CA_FILE=/etc/back-office/extra-ca.pem
```

The controller checks the file at start (certificates only, no private key) and gives it to every job; the
runner adds it to the system certificates for git, npm, Java and Gradle, Python and Go tools. The job log says
"trusting N extra CA certificate(s)". Nothing else changes: every TLS check stays on.

<a id="security"></a>
## Before you use it

- **Licence.** Check that your Claude plan or your organisation's agreement allows Claude Code to be used this way
  (automated steps started from another system). If in doubt, ask whoever administers your Claude licence, or use the
  gateway.
- **Your login is inside the sandbox.** Claude Code needs the token, so the agent could read it. Use your own login
  only for repositories and issues you trust; the sandbox still reaches only Back Office, your git host, the
  package registries and `api.anthropic.com` (the egress proxy blocks everything else). See
  [Prompt injection](agent-execution.md#prompt-injection).
- **Docker access.** The controller controls your Docker daemon (needed to start sandboxes). Run it only on your own
  computer. The DOCKER backend uses runc, not gVisor: the isolation is weaker than on the shared fleet.
