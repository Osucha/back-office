# Git access for agents

Coding agents clone the project's repository, push a branch and, on GitLab, open a merge request. They do
this from their sandbox with git tokens that live **only in the runner cluster**. The Back Office
control plane has no git credential and never calls an SCM API. This chapter covers the least-privilege
setup (a read-only token and a push token per repository), creating the tokens on GitLab or GitHub,
protecting the default branch so that no agent token can merge, installing the tokens into the runner pool,
and rotating them.

**Who needs this:** platform administrator or runner-cluster operator; GitLab/GitHub maintainer of the
repositories.

<a id="git-overview"></a>
## What the agent does with git

For every **Spec Kit** or **Implement** step (and coding benchmark attempts) the runner inside the sandbox
(`runner/entrypoint.py`):

1. Clones the project's **Git URL** over HTTPS (`git clone`), checks out the base ref, fetches or creates
   the run branch (`git ls-remote`, `git fetch`), by default `feature/<issue key>`.
2. Commits as **Back Office Agent** `<agent@backoffice.local>` unless you set another identity; every commit
   carries the trailer `Back-Office-Run: <runId>`, which CI uses to signal the run.
3. Pushes the branch, but only when the job's **git access** is `PUSH_MR` (see below; `push` is on by
   default in the task configuration).
4. For **Implement** steps with `mergeRequest` on (the default) it adds GitLab push options:
   `-o merge_request.create -o merge_request.target=<default branch> -o merge_request.title=...`
   (`description`, `draft` when configured). GitLab opens or updates the merge request and prints its URL,
   which Back Office records on the run. The runner **never** sends options that merge
   (`merge_request.merge_when_pipeline_succeeds`, `merge_request.auto_merge`), remove the source branch or
   skip CI, and never force-pushes.

Authentication: an inline git credential helper answers with user name `oauth2` and the token read from
the file `/var/run/backoffice/git/token`. It is used only for clone, fetch, ls-remote and (PUSH_MR jobs)
push; the token is never written into `.git/config`, the environment of the agent or the logs.

Nothing calls the GitLab or GitHub API, so no token needs an API scope.

**Several repositories.** A project can have several repositories, and a step can use several of them
([Repositories of a step](workflows.md#step-repositories)). The runner then does the steps above for each
repository, in its own folder `/workspace/<key>`, with the access the step gives that repository: **Read**
repositories are cloned with the read token and never pushed, **Push and merge request** repositories get the
push token, the same branch and their own merge request. Install read and push tokens for every repository
(or group) that steps use; the pool selects them per repository exactly as described below. Upgrade runner
images and runner-controllers before using steps with several repositories: older runners clone only the
primary repository, and older controllers select one token, for the primary repository, at the highest access
the step uses.

**Limits**

- The token file is inside the agent's container: a determined agent could read it. That is why each job
  gets only the rights it needs, scoped to one repository (or group), with a short lifetime.
- Every repository **URL** of the project must be an `https://` URL. SSH URLs do not use the token.
- Merge requests are created only on GitLab. Other hosts get the pushed branch only.

<a id="git-least-privilege"></a>
## Least privilege: read-only and push tokens

Back Office derives a **git access level** for every agent job and sends only the level to the runner pool,
never a secret. The runner-controller picks the matching token of its cluster:

| Level | Which jobs | Token the sandbox gets | What the runner does |
|---|---|---|---|
| `NONE` | jobs without a repository | none | nothing with git |
| `READ` | steps with `push` off, Spec Kit analysis without push, coding benchmarks | the **read** token | clone and fetch only; pushing is disabled (`remote.origin.pushurl` = `disabled://read-only-job`) and the runner never pushes |
| `PUSH_MR` | **Implement** and **Spec Kit** steps with `push` on | the **push** token | pushes the job branch and opens a merge request with push options; never merges |

Where you see it: in the workflow designer the `push` field of an Implement or Spec Kit node says which
level it produces; on **Administration** → **Runner fleet** → **Agent jobs** the job panel shows **Git
access**; the run's step log has `[git] access READ: clone only, pushing is disabled`.

What you set up, per repository (or per group):

1. A **READ token**: can clone, cannot push ([GitLab](#gitlab-read-token)).
2. A **PUSH token**: can create and push branches and open merge requests, cannot merge into the default
   branch ([GitLab](#gitlab-push-token)).
3. **Protected default branch** so that the push token cannot merge, whatever it sends
   ([GitLab](#gitlab-protect-branches)).
4. Both tokens as Secrets in the runner cluster, mapped to the repository in `gitCredentials.repoSecrets`
   ([Install](#git-install)).

If a READ job finds only a push token for its repository (for example a pool still configured with the
older single `gitCredentials.existingSecret`), the job **fails** with
`policy-violation: git credential: READ job for <repository>, but ... has only a push credential`. Add a read
token, or set `gitCredentials.allowPushCredentialForRead: true` as a conscious, temporary exception. A PUSH_MR
job whose repository has only a read token fails with `... has no push credential`.

<a id="gitlab-token"></a>
## GitLab: which token type

Pick one token type, best first (GitLab UI as of 2026; check the linked docs if a menu moved). Create **two**
tokens of that type per repository or group: one for READ, one for PUSH.

| Token | Available on | Covers | Notes |
|---|---|---|---|
| **Project access token** | GitLab Self-Managed and Dedicated; GitLab.com Premium/Ultimate | one repository | Creates a bot user that is a member of that project only. Best choice |
| **Group access token** | same | all projects of a group | For a pool that serves many repositories of one group |
| Personal access token of a **bot user** | everywhere, incl. GitLab.com Free | every project the bot is a member of | Create a dedicated user, add it as Developer to the projects, create the tokens as that user |

Docs: project access tokens https://docs.gitlab.com/user/project/settings/project_access_tokens/, group
access tokens https://docs.gitlab.com/user/group/settings/group_access_tokens/, personal access tokens
https://docs.gitlab.com/user/profile/personal_access_tokens/.

<a id="gitlab-read-token"></a>
## GitLab: READ token (clone only)

1. In GitLab open the repository → **Settings** → **Access tokens** → **Add new token**. You need the
   Maintainer role. (Group: **Group** → **Settings** → **Access tokens**.)
2. **Token name**: `back-office-read-<pool>`.
3. **Expiration date**: at most **90 days** from today.
4. **Select a role**: **Reporter**. Reporter can read the code but cannot push to any branch.
5. **Select scopes**: only **read_repository**. Leave `api`, `read_api`, `write_repository` and everything
   else unticked.
6. **Create project access token** and copy the value (`glpat-...`); it is shown once.

GitLab.com Free (no project or group access tokens): sign in as the bot user (see below) → avatar →
**Edit profile** → **Access tokens** → **Add new token**, scope **read_repository** only, expiry at most 90
days. The scope alone makes it read-only, even though the bot has the Developer role.

<a id="gitlab-push-token"></a>
## GitLab: PUSH token (branch, push, merge request; no merge)

1. Repository → **Settings** → **Access tokens** → **Add new token** (or the group's access tokens).
2. **Token name**: `back-office-push-<pool>`.
3. **Expiration date**: at most **90 days**.
4. **Select a role**: **Developer**. Developer can create and push unprotected branches and create merge
   requests (also through push options), but cannot push to or merge into a protected branch that allows
   only Maintainers. Never use Maintainer or Owner.
5. **Select scopes**: only **write_repository** (it includes reading the repository). **No** `api`, **no**
   `read_api`: the runner opens merge requests with git push options, which need no API scope, and without
   `api` the token cannot approve, merge or change settings through the API.
6. **Create project access token** and copy the value.

GitLab.com Free: create a dedicated user such as `backoffice-bot`, add it to each repository with the role
**Developer** (**Manage** → **Members** → **Invite members**), sign in as the bot and create a personal access
token with scope **write_repository** only, expiry at most 90 days. Use a second personal access token of the
same bot with **read_repository** only as the READ token.

**Check the repository settings**

- The run branches (`feature/...`) must not be protected against Developers pushing.
- Push rules that require the commit author to be a GitLab user, or signed commits, reject the agent's
  commits. Set the commit identity to the bot's e-mail (see [Install](#git-install)) or relax the rule.
- The target branch (the project's **Default branch** in Back Office) must exist.

<a id="gitlab-protect-branches"></a>
## GitLab: make merging impossible for the agent token

The runner never asks GitLab to merge. These settings make sure that even a stolen push token cannot:

1. Repository → **Settings** → **Repository** → **Protected branches** → **Expand**. Docs:
   https://docs.gitlab.com/user/project/repository/branches/protected/
2. For the default branch (`main`; add it with **Add protected branch** if it is missing):
   - **Allowed to merge**: **Maintainers**;
   - **Allowed to push and merge**: **No one** (or **Maintainers**);
   - **Allowed to force push**: off;
   - **Require approval from code owners**: on, if you use a `CODEOWNERS` file.
   Protect release branches (`release/*`) the same way.
3. Recommended: **Settings** → **Merge requests** → **Merge checks** → **Pipelines must succeed** on, and
   **All threads must be resolved** on.
4. GitLab Premium/Ultimate: **Settings** → **Merge requests** → **Merge request approvals** → add an approval
   rule with at least 1 approval from people; under **Approval settings** tick **Prevent approval by merge
   request creator** and **Prevent approvals by users who add commits**. The agent's bot user then cannot
   approve its own merge request either.
5. For new projects of a group: **Group** → **Settings** → **Repository** → **Default branch** → **Initial
   default branch protection**: **Fully protected**.

Why this is enough: the push token has the **Developer** role and the default branch allows merges by
**Maintainers** only. GitLab refuses the merge for a Developer, whether it comes from the UI, the API or a
push option such as `merge_request.merge_when_pipeline_succeeds`. The token has no `api` scope, so it cannot
change these settings or approve anything either.

<a id="github-token"></a>
## GitHub: create the tokens

Use **fine-grained personal access tokens** limited to the repositories the pool works on, created by a
dedicated machine user (recommended).

1. Sign in as the machine user → **Settings** → **Developer settings** → **Personal access tokens** →
   **Fine-grained tokens** → **Generate new token**.
   Docs: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens
2. **Resource owner**: the organisation that owns the repository (the organisation may require approval
   of fine-grained tokens; an owner approves under **Settings** → **Personal access tokens** → **Pending requests**).
3. **Expiration**: 90 days or less.
4. **Repository access**: **Only select repositories**, pick the repository.
5. **Repository permissions**:
   - READ token: **Contents: Read-only** (**Metadata: Read-only** is added automatically). Nothing else.
   - PUSH token: **Contents: Read and write**. Add **Workflows: Read and write** only if agents may change
     files under `.github/workflows/`; without it GitHub refuses such pushes. No **Pull requests**,
     **Administration** or other permissions.
6. **Generate token** and copy it (`github_pat_...`).

GitHub accepts the token as the HTTPS password with the user name `oauth2` that the runner sends.

**Make merging impossible on GitHub.** Contents write would let a token push to, or merge into, any
**unprotected** branch through git or the API, so protect the default branch: repository → **Settings** →
**Rules** → **Rulesets** → **New branch ruleset** (or **Branches** → **Add branch protection rule**), target
the default branch, enable **Restrict deletions**, **Require a pull request before merging** with at least 1
required approval, **Require status checks to pass**, **Block force pushes**, and leave the bypass list
without the machine user. The machine user then cannot push to or merge into the default branch; GitHub
also never lets the author of a pull request approve it.

**Pull requests on GitHub.** GitHub ignores GitLab push options, so the agent pushes the branch and the
run has no merge request URL. Do this instead:

1. In the workflow designer select the **Implement** node and set the task configuration `mergeRequest`
   to `false` (keep `push` on), or make the change in a project-level copy of the template
   ([task-library.md](task-library.md#templates)).
2. Open the pull request from GitHub Actions on push of `feature/**` branches (for example with
   `gh pr create --fill`, which needs `pull-requests: write` for the workflow's `GITHUB_TOKEN`), or by hand.

Other hosts (Gitea, Bitbucket, Azure Repos) work the same way as GitHub: a read-only token and a token with
repository write access used as HTTPS passwords, branch protection on the default branch, `mergeRequest` set
to `false`.

<a id="git-project-tokens"></a>
## Repository access tokens in the project

Since ADR 0051 a project keeps the tokens of its repositories itself, so they no longer have to be installed in
every runner. A project owner adds them on the project's **Sources** tab, card **Repository access tokens**.
They are being introduced step by step: in this release the tokens are stored, tested and shown, and jobs still
use the runner pool's tokens below. Later releases switch cloning and pushing to the project's tokens, deliver
them to the runner encrypted per job, and give agents read-only tools such as the CI logs.

- **One token per permission level.** A repository can have several tokens. Each one declares what it is for:

  | Capability | What Back Office uses it for | GitLab needs |
  |---|---|---|
  | GIT_READ | clone the repository | `read_repository`, role Reporter |
  | CI_READ | read pipelines and job logs | `read_api`, role Reporter |
  | PUSH_BRANCH | push the agent's branch | `write_repository`, role Developer |
  | MR_WRITE | create and update merge requests | `api`, role Developer |
  | MR_COMMENT | post review comments | `api`, role Reporter |
  | PIPELINE_RUN | retry or start pipelines | `api`, role Developer |

  Read capabilities (GIT_READ, CI_READ) may later be handed to an agent as a tool, but only when the token is
  verified read-only. Write capabilities are used only by Back Office itself, never by an agent.
- **A typical setup:** a project access token with role Reporter and scopes `read_repository` and `read_api`
  (GIT_READ, CI_READ), and one with role Developer and scope `write_repository` (PUSH_BRANCH). Never use role
  Maintainer: it can merge.
- **Tested on save.** **Test before saving**, every save and **Test again** ask GitLab what the token can do: clone
  and push (over git), its scopes and expiry, its role in the project and the default branch's protection. The
  result lists the granted and missing capabilities and the **write powers** the token has beyond what it
  declares, for example "git push", "api scope" or "merge into main". A missing capability marks the token as
  failed; excess rights, an unprotected default branch or role Maintainer give a warning. The test only reads.
- **Never shown again.** The token is encrypted with the installation's secret key (as Jira tokens are) and never
  returned. Everyone who can see the project sees the list; only owners add, change, test and delete tokens.
  Every change is audited (`SCM_CONNECTION_*`).
- **Expiry.** Without an expiry date the one GitLab reports is used; tokens close to expiry are highlighted.
- A token whose repository was removed from the project is shown as **orphaned** and is never used.

<a id="git-install"></a>
## Install the tokens in the runner pool

The tokens go into the cluster where the pool's runner-controller runs, never into Back Office.

**Kubernetes (chart `back-office-runner`)**

1. Find the sandbox namespace: by default `<release namespace>-sandboxes`, for example
   `bo-runner-eu-1-sandboxes` when you installed with the command from **Add runner**. The namespace is
   created by the chart; create the Secrets after the first install, or pre-create the namespace and set
   `sandboxNamespace.create=false`.
2. Create one Secret per token with the key `token` in **that** namespace. Read the value without echoing
   it so it does not land in the shell history:

   ```bash
   NS=bo-runner-eu-1-sandboxes
   read -rs TOKEN   # paste the READ token (glpat-...), Enter
   kubectl -n "$NS" create secret generic git-shop-api-read --from-literal=token="$TOKEN"
   read -rs TOKEN   # paste the PUSH token, Enter
   kubectl -n "$NS" create secret generic git-shop-api-push --from-literal=token="$TOKEN"
   unset TOKEN
   kubectl -n "$NS" get secret git-shop-api-read git-shop-api-push
   ```

3. Map them to the repository in the pool's values file and upgrade:

   ```yaml
   gitCredentials:
     repoSecrets:
       # one repository (host/path of the project's Git URL, without .git)
       - repository: gitlab.example.com/shop/api
         read: git-shop-api-read
         push: git-shop-api-push
       # every repository of a group (group access tokens)
       - repository: gitlab.example.com/shop/*
         read: git-shop-read
         push: git-shop-push
     # commit identity, e.g. the bot user's address (push rules)
     userName: Back Office Agent
     userEmail: backoffice-bot@example.com
   ```

   `helm upgrade --install bo-runner-eu-1 deploy/helm/back-office-runner -n bo-runner-eu-1 -f runner-eu-1.values.yaml`

| Helm value | Meaning |
|---|---|
| `gitCredentials.repoSecrets` | list of `{repository, read, push}`. `repository`: `host/group/project`, `host/group/*`, `host`, `*.example.com` or `*`; the most specific entry wins. `read` = Secret for READ jobs, `push` = Secret for PUSH_MR jobs; either may be left out |
| `gitCredentials.allowPushCredentialForRead` | `false` (keep it): a READ job that only finds a push Secret fails instead of receiving it |
| `gitCredentials.existingSecret` / `hostSecrets` | older single-token setup, treated as **push** tokens for every repository (or host) without a `repoSecrets` entry; the controller logs a warning. Migrate to `repoSecrets` |
| `gitCredentials.userName` / `gitCredentials.userEmail` | commit identity (`RUNNER_GIT_USER_NAME` / `RUNNER_GIT_USER_EMAIL`); empty = `Back Office Agent` / `agent@backoffice.local` |

4. The controller mounts only the Secret of the job's level into each sandbox (warm sandboxes receive a copy
   in memory) at `/var/run/backoffice/git/token`; READ jobs never see the push token. Repositories are matched
   by the host and path of the project's **Git URL** (lower case, without port and `.git`).
5. The git host is always added to the job's egress allowlist. A self-hosted GitLab on a private address
   also needs `egressProxy.allowedCidrs` (see [agent-execution.md](agent-execution.md#network)).

**Upgrading from one token** (`existingSecret` / `hostSecrets`): implementation jobs keep working; READ jobs
(benchmarks, steps with `push` off) fail with the `git credential` reason until you add a read Secret in
`repoSecrets`. The controller's start-up log lists the push-level fallbacks it still uses.

**Docker Compose (laptop, profile `full`)**: set `GIT_TOKEN_READ` and `GIT_TOKEN_PUSH` (and optionally
`GIT_USER_NAME`, `GIT_USER_EMAIL`) in `deploy/compose/.env`. Compose passes them to the runner-controller as
`BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH`, which writes the one matching the job's level into each sandbox.
The older `GIT_TOKEN` (`BO_GIT_TOKEN`) still works as a push token. `docker compose --profile full up -d runner-controller`.

**Test it**

1. Start a run of a workflow with an **Implement** step on a small issue.
2. On the run page the step log shows `[git] remote: ...` lines; on GitLab the last one contains the merge
   request URL, which also appears on the run. The agent job panel shows **Git access** `PUSH_MR`.
3. Start a coding benchmark (or a step with `push` off): its job shows **Git access** `READ`, the log has
   `[git] access READ: clone only, pushing is disabled`, and nothing is pushed.
4. Failures read `git clone failed` (token or URL wrong, egress), `git push failed (...)` (rights, protected
   branch, push rules), `the receiving end does not support push options` (not GitLab: switch
   `mergeRequest` off) or `policy-violation: git credential: ...` (no read or push Secret for that repository).

<a id="git-multi-repo"></a>
## Several repositories in one job

A job can work with several repositories at once, for example the service it changes plus a documentation
repository it only reads, or a library and the application that uses it. Each repository of the job has its
**own** access level, and the sandbox gets one token per repository, chosen for that repository and that level
only:

| Repository in the job | Access | Token the sandbox gets | What happens |
|---|---|---|---|
| `src` (`gitlab.example.com/shop/api`, primary) | `PUSH_MR` | the **push** token of `shop/api` | the agent works here; the job branch is pushed and a merge request opened |
| `docs` (`gitlab.example.com/shop/docs`) | `READ` | the **read** token of `shop/docs` | cloned for reference; local edits are reported, never pushed (`remote.origin.pushurl` disabled) |
| `tools` (a public repository) | `NONE` | none | cloned anonymously |

How the sandbox is laid out: every repository is cloned into `/workspace/<path>` (by default its key). The agent,
`setupCommand`, `verifyCommand`, Spec Kit and skills run in the **primary** repository; the others are next to
it (`../docs`) and the agent may edit them. Its prompt ends with a short **Repositories** list (key, path,
access, purpose). A job without any repository starts in an empty `/workspace`.

Install the tokens exactly as for one repository ([Install](#git-install)): one `repoSecrets` entry per
repository (or group), with the Secret of each level the repository is used with. A typical setup with a
read-only documentation repository and push-capable source repositories:

```yaml
gitCredentials:
  repoSecrets:
    # documentation: agents only read it, so there is deliberately no push Secret
    - repository: gitlab.example.com/shop/docs
      read: git-shop-docs-read        # project access token: Reporter, read_repository
    # source repositories of the group: read for reviews, push for implementation
    - repository: gitlab.example.com/shop/*
      read: git-shop-read             # group access token: Reporter, read_repository
      push: git-shop-push             # group access token: Developer, write_repository
```

The more specific `shop/docs` entry wins over `shop/*`, so the documentation repository can never receive the
group's push token. A job that would need it fails before a sandbox starts, naming the repository:
`policy-violation: repository 'docs' (gitlab.example.com/shop/docs): git credential: PUSH_MR job for ..., but ...
has no push credential`. The same applies to a READ repository that only has a push Secret (unless
`allowPushCredentialForRead`).

Inside the sandbox each token sits in its own file (`/var/run/backoffice/git/<key>/token`), is read once and
deleted before the agent starts, and is answered by the credential helper only for its own repository's host
**and** path, so the docs token is never sent to the source repository on the same GitLab and vice versa. All
repository hosts are added to the job's egress allowlist.

Results: the run shows one entry per repository (branch, commit, changed files, merge request URL, pushed or
not, error). One repository failing to push does not hide the others; the job is marked failed when a
`PUSH_MR` repository failed, and the error names it, for example `repository src: push failed: ...`.

**Docker Compose**: `GIT_TOKEN_READ` and `GIT_TOKEN_PUSH` apply to every repository; each repository of the
job gets the one of its own level.

<a id="git-rotate"></a>
## Rotate the tokens

Rotate both tokens before they expire (at most every 90 days); put a reminder in your calendar or use
GitLab's expiry e-mails.

1. Create the new token as above (GitLab also offers **Rotate** on the token row, which issues a new value
   and revokes the old one at once: only use it when you can update the Secret immediately).
2. Replace the Secret value:
   `read -rs TOKEN; kubectl -n <ns>-sandboxes create secret generic git-shop-api-push --from-literal=token="$TOKEN" --dry-run=client -o yaml | kubectl apply -f -; unset TOKEN`
3. Nothing else restarts: every new sandbox reads the Secret when it starts. Jobs already running keep the
   old token, so wait until **Administration** → **Runner fleet** → **Agent jobs** shows none running for
   the pool.
4. Revoke the old token in GitLab or GitHub. Repeat for the READ token.

On Docker Compose change `GIT_TOKEN_READ` / `GIT_TOKEN_PUSH` in `.env` and recreate the runner-controller
container.
