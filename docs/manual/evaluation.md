# Models and evaluation

Model profiles are the catalogue of models that tasks and benchmarks pick from, with their options and
prices. Evaluation measures those models on your own kind of work, so you choose them on numbers rather
than opinions. This chapter covers the **Models** page, task suites and benchmarks.

| You want to | Permission | Built-in role |
|---|---|---|
| See model profiles | `models.view` | **Member** |
| Create, edit, delete and test profiles | `models.manage` | **Platform administrator** |
| See suites, benchmarks and leaderboards | `evaluation.view` | **Member** |
| Create suites and tasks; create, start and cancel benchmarks | `evaluation.run` | **Evaluator** |

<a id="models"></a>
## Model profiles

**Models** (`/models`) lists the profiles with **Provider**, **Model id**, price **In / out per MTok**,
**Context**, **Last test** and **Enabled**. No profiles are predefined: you create them from the models
your connected providers offer (see [LLM providers](integrations-llm.md#llm-overview)). Profiles whose
provider is not connected are hidden; the line under the table counts them and **Show them** brings them
back, to fix their connection or delete them.

Below the profiles, **Available from providers** lists the models every connected provider offers right
now, read live from the provider (refreshed every ten minutes, or with the reload button). OpenRouter also
publishes prices and context windows; its `:free` models are marked **free**, and **Free only** shows just
those. **Add as profile** opens the profile form prefilled with the provider, connection, model id, name,
prices and context window; models that already have a profile say **profile exists**.

A profile is a model plus its options. Two profiles may point at the same model with different options,
for example high and low reasoning, so you can measure whether the stronger setting is worth its price.

### Steps: create a profile

1. Click **Add as profile** on a model under **Available from providers**, or **Create profile** for a
   model the provider does not list (or duplicate an existing profile from its row).
2. Enter **Display name**, **Provider** and **Model id**, exactly as the provider's API expects it.
3. **Default options**: **Temperature**, **Max output tokens**, **Reasoning effort**, and **Extra options
   (JSON)** passed to the provider as they are.
4. **Pricing** in USD per million tokens: **Input** and **Output** (required), **Cache read** and **Cache
   write**.
5. **Catalogue**: **Capabilities** (informational tags), **Context window (tokens)**, **Notes**.
6. Leave **Enabled** on. Disabled profiles are hidden from the pickers of steps and benchmarks.
7. Click **Create profile**, then **Test profile**: it sends a tiny prompt and shows the latency and cost,
   or the provider's error.

Cost figures everywhere in Back Office are computed from these prices. A profile with a placeholder price
of 0 makes a model look free; check prices before you trust **Costs** or a leaderboard.

### Where profiles are used

| Place | Setting |
|---|---|
| A project | **Default model profile** and **Review model profile** (see [Projects](projects.md#create-project)) |
| A task template | **Model profile** (see [Task library](task-library.md#templates)) |
| A task node | **Model profile** override (see [Designing workflows](workflows.md#nodes)) |
| A benchmark | candidate models and the judge model |

A node's profile wins over the template's, which wins over the project default.

<a id="model-availability"></a>
### Which models a step can use

A model is either a **platform** model (a provider connection added by an administrator, called through the API,
usable by everyone and counted against budgets) or a **personal** one (today: Claude Code with your own login on
your personal runner, usable only by you). Personal models work only in your private projects and your own
benchmarks; platform models work everywhere, private projects included.

Wherever you pick a model (a step in the workflow designer, the project's default and review models, the
start dialog) Back Office lists the models in two groups, **Platform** and **Personal**, each with its route
(**API** or **your login**). A model that cannot be used there is greyed out with the reason, for example:

| Reason | What to do |
|---|---|
| the provider connection is not set up or has no API key | connect the provider in Settings (administrator) or pick another model |
| a Claude Code login model runs coding-agent steps only | LLM steps (analysis, task list, review) need a platform model |
| works only in private projects | use a platform model in shared projects |
| needs Claude Code as the coding agent | set the step's coding agent to Claude Code |
| no personal runner, or it does not use your login | set it up under My account, Personal runner |

A personal runner that is offline is only a warning: the jobs wait until it connects.

The **model plan** of a workflow shows, for every step, the model a run would use and where it comes from (the
step, its template, the project default or the review default). The designer marks steps whose model you cannot
use with a warning, and a run does not start while a step has no usable model; the start dialog lists the steps
and the reason. The MCP tools `get_model_options` and `get_model_plan` give an IDE agent the same information.

<a id="claude-code-login"></a>
### Claude Code with your own login

If your personal runner signs Claude Code in with your own Claude subscription (**My own Claude login
(subscription)**, see [Personal runner](personal-runner.md#credentials)), you can benchmark
it without an API key. Under **Available from providers**, the source **Claude Code (your login)** offers `opus`, `sonnet` and `haiku`; **Add as profile**
creates a profile marked **Claude Code login**. Such a profile:

- runs as Claude Code on the personal runner of whoever creates the benchmark, with that person's login;
- needs **My personal runner** as the execution target, **Claude Code** as the coding agent and coding tasks
  only (prompt tasks through Claude Code come later);
- cannot be the judge: choose a model of a connected provider for that;
- costs nothing in Back Office (the subscription pays), but uses up the subscription's usage limits;
- is not offered to projects, templates or workflow steps.

**Test profile** and **Check models** do not call the model for it: they check that your personal runner
exists, uses your login and is online. Claude Code adds its own instructions and start-up time, so compare such
a candidate with API models on scores, not on response times.

A subscription token (`sk-ant-oat...`, from `claude setup-token`) is not an API key: an Anthropic provider
connection refuses it.

<a id="suites"></a>
## Task suites

A task suite is the work models are measured on: your kind of tickets, specs, reviews and coding jobs.
**Evaluation** → **Task suites** (`/evaluation/suites`) lists them. A starter suite, **Starter: SDLC
tasks**, shows the shape; copy it and replace the content with your own work. Its five coding tasks run against
Spring PetClinic (`https://github.com/spring-projects/spring-petclinic`, pinned to one commit, verified with
`./mvnw -B test` in about a minute) with hidden acceptance tests, so a first benchmark works without your own
repository. Starter tasks you have not edited are updated to the current version at the next start.

### Steps: create a suite and add tasks

1. Click **Create suite**, enter **Name**, **Description** and the **Coding score weights**
   (**Verification** and **Judge**, summing to 1), and click **Create suite**.
2. Open the suite and click **Add task**.
3. Fill in **Name**, **Category**, **Difficulty** and **Kind**:
   - **Prompt**: one model call with the **Instructions (markdown)** and optional **Context**; the judge
     scores the answer against the **Rubric (markdown)** and an optional **Reference answer**.
   - **Coding job**: runs in an agent job. Set the **Repository**: **Clone URL**, **Base ref**, **Setup
     command** and **Verify command** (exit code 0 means passed). The agent works on a branch that is
     never pushed. **Acceptance checks (JSON)** are hidden from the agent: `files` (path → content, for
     example acceptance tests) are written only for the verify command and removed afterwards,
     `protectedPaths` (git pathspecs such as `src/test/**`) must stay unchanged, and `requireChanges` fails
     a run that changed nothing.
4. Under **Execution**, set **Timeout (seconds)** and **Weight** (relative weight in suite aggregates).
5. Check the **Preview** tab, then click **Add task**.

Use **Edit suite** and **Delete suite** from the suite page's menu; **Benchmark this suite** starts a new
benchmark for it.

### Writing a good rubric

- Name 3 to 5 criteria, each scored 0–10, with one line saying what a 10 looks like.
- Put what you would reject in a review into the rubric explicitly, for example "no technology names".
- State caps for decisive mistakes, for example "a wrong root cause caps this criterion at 3".
- Give a reference answer when the task has a known answer. The judge takes it as authoritative on facts
  (root causes, planted defects, rules); other wording or equally valid designs are fine.
- Grade only what the instructions asked for.

Good suites have 8 to 20 tasks across categories and difficulties, at least two per category. Coding
tasks need a repository the runner can clone and a verify command that is green on the base ref. A coding
task without hidden acceptance tests mostly measures whether the agent broke nothing: write tests that
fail on the base ref and pass with a reference solution, and check both before you add them.

**Scoring.** The judge scores each rubric criterion 0–10, evidence first; the task score is the mean × 10,
computed by Back Office. Prompt tasks pass at 70. Coding jobs score
`verification × (passed ? 100 : 0) + judge × judge score` with the suite's weights, and pass only when the
verification (including the acceptance checks) passed and the score is 70 or more. For coding jobs the
judge reads the diff against the base ref, not the agent's summary alone.

<a id="benchmarks"></a>
## Benchmarks

A benchmark runs a suite against several models, scored by one fixed judge.
**Evaluation** → **Benchmarks** (`/evaluation/benchmarks`) lists them with holder, suite, number of
models, attempt progress and cost.

### Steps: run a benchmark

1. Click **Create benchmark**.
2. Enter **Name** and pick the **Task suite**. Optionally pick **Tasks**; empty runs every task.
3. Pick the **Candidate models**. Profiles whose provider has no key cannot be picked.
4. Pick the **Judge model**. Use the strongest model you can afford, and never one of the candidates.
5. Set **Repetitions** (1–5); two or three measure how stable the scores are.
6. For coding jobs, choose the **Coding harness**: **OpenCode** (works with every provider) or **Claude
   Code** (Anthropic only).
7. When the tasks include coding jobs, choose where they run under **Coding tasks run on**: **Shared
   runners** (the platform's fleet) or **My personal runner** (your computer, see
   [Personal runner](personal-runner.md)). Each option says how many runners are online; one that is not set
   up cannot be chosen, and with none online the coding attempts wait until a runner connects (at most 24
   hours). Prompt tasks need no runner.
8. Click **Create and start**, or **Save as draft** and later **Start benchmark** on its page. **Create and
   start** first sends a one-line test to every candidate and the judge (**Check models** does only that):
   models that do not answer are listed with the provider's reason, for example no access to the model or
   a daily quota used up. Remove them, or click **Start anyway**.

The form shows how many attempts the benchmark plans (tasks × models × repetitions, each also judged
once). Every attempt costs candidate tokens plus judge tokens; coding attempts on frontier models can
cost several dollars each. The form warns when the judge is also a candidate (it would grade its own answers)
and when coding tasks point at a sample repository such as `gitlab.example.com` that does not exist.

### While it runs

Attempts are planned up front and run a few at a time per provider. When the operator has configured
provider rate limits, benchmark calls leave a reserve for workflow steps and reviews, and an attempt that
hits the limit is queued again later. A retry after only the judge failed keeps the candidate's answer and asks
the judge again. Progress updates live. **Cancel benchmark** stops queued attempts and running jobs; finished
attempts keep their results. Single failed attempts do not fail the benchmark.

Some failures repeat for every further call: no access to the model, a wrong key, a daily quota of the
provider or of the connection used up. After the first one, the attempts of that model still waiting end at
once with "skipped, ... failed: ..." instead of each calling the provider; when the judge fails that way, all
waiting attempts end. When coding attempts wait because no runner is online where the benchmark sends them,
its page says so.

### Reading the results

The benchmark page has the tabs **Leaderboard** and **Attempts · N**.

| Leaderboard column | Meaning |
|---|---|
| **Score** | Mean judge score over successful attempts. |
| **Pass rate** | Share of attempts that passed (coding: verification and acceptance checks green and a score of 70 or more; prompt: score of 70 or more). Failed and timed-out attempts count as not passed. |
| **Mean cost**, **Total** | Candidate cost per attempt and overall. |
| **Score / $** | Mean score per dollar of mean cost. |
| **Time p50 / p95** | The candidate's own response time (the model's answer, or the agent job from submission to result), without the judge, queueing or retries, over succeeded attempts: half of them were faster than p50, 95% faster than p95. Below 20 attempts p95 is simply the slowest one; the tooltip says how many attempts the times come from. |
| **Stability** | Standard deviation of the score across repetitions; lower is steadier. |

**Score vs cost** plots every model; a model that another one beats on both score and cost is dominated.
The breakdowns **Overall**, **By category**, **By difficulty** and **By task** repeat the table per slice.
In **Attempts**, filter by task and model and open an attempt to read the output, the judge's criteria
and reasoning, and the verification output. Read the worst attempt of each model before concluding
anything: a low score is often a rubric problem.

### Applying the result

1. Set the cheapest profile that clears your bar as the project's **Default model profile**.
2. Keep a stronger profile only on the task nodes where the benchmark showed a real gap.
3. Prefer the winning profile (model plus reasoning level), not only the model.
4. Run the benchmark again when a provider ships a new model or changes prices.

Benchmark spend appears in **Costs** under **By benchmark** (see [Costs](costs-insights.md#costs)).
