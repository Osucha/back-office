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
**Context**, **Last test** and **Enabled**. A notice at the top names providers that have no API key on
the server; tests and runs with their profiles fail until the key is set (see
[LLM providers](integrations-llm.md#llm-overview)).

A profile is a model plus its options. Two profiles may point at the same model with different options,
for example high and low reasoning, so you can measure whether the stronger setting is worth its price.

### Steps: create a profile

1. Click **Create profile** (or duplicate an existing one from its row).
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

<a id="suites"></a>
## Task suites

A task suite is the work models are measured on: your kind of tickets, specs, reviews and coding jobs.
**Evaluation** → **Task suites** (`/evaluation/suites`) lists them. A starter suite, **Starter: SDLC
tasks**, shows the shape; copy it and replace the content with your own work.

### Steps: create a suite and add tasks

1. Click **Create suite**, enter **Name**, **Description** and the **Coding score weights**
   (**Verification** and **Judge**, summing to 1), and click **Create suite**.
2. Open the suite and click **Add task**.
3. Fill in **Name**, **Category**, **Difficulty** and **Kind**:
   - **Prompt**: one model call with the **Instructions (markdown)** and optional **Context**; the judge
     scores the answer against the **Rubric (markdown)** and an optional **Reference answer**.
   - **Coding job**: runs in an agent job. Set the **Repository**: **Clone URL**, **Base ref**, **Setup
     command** and **Verify command** (exit code 0 means passed). The agent works on a branch that is
     never pushed.
4. Under **Execution**, set **Timeout (seconds)** and **Weight** (relative weight in suite aggregates).
5. Check the **Preview** tab, then click **Add task**.

Use **Edit suite** and **Delete suite** from the suite page's menu; **Benchmark this suite** starts a new
benchmark for it.

### Writing a good rubric

- Name 3 to 5 criteria, each scored 0–10, with one line saying what a 10 looks like.
- Put what you would reject in a review into the rubric explicitly, for example "no technology names".
- Give a reference answer when the task has a known answer; the judge treats it as one good answer.
- Grade only what the instructions asked for.

Good suites have 8 to 20 tasks across categories and difficulties, at least two per category. Coding
tasks need a repository the runner can clone and a verify command that is green on the base ref.

**Scoring.** Prompt tasks get the judge's score (0–100) and pass at 70. Coding jobs score
`verification × (passed ? 100 : 0) + judge × judge score`, with the suite's weights.

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
7. Click **Create and start**, or **Save as draft** and later **Start benchmark** on its page.

The form shows how many attempts the benchmark plans (tasks × models × repetitions, each also judged
once). Every attempt costs candidate tokens plus judge tokens; coding attempts on frontier models can
cost several dollars each.

### While it runs

Attempts are planned up front and run a few at a time per provider. When the operator has configured
provider rate limits, benchmark calls leave a reserve for workflow steps and reviews, and an attempt that
hits the limit is queued again later. Progress updates live. **Cancel benchmark** stops queued attempts and running
jobs; finished attempts keep their results. Single failed attempts do not fail the benchmark.

### Reading the results

The benchmark page has the tabs **Leaderboard** and **Attempts · N**.

| Leaderboard column | Meaning |
|---|---|
| **Score** | Mean judge score over successful attempts. |
| **Pass rate** | Share of attempts that passed (coding: tests green; prompt: score of 70 or more). Failed and timed-out attempts count as not passed. |
| **Mean cost**, **Total** | Candidate cost per attempt and overall. |
| **Score / $** | Mean score per dollar of mean cost. |
| **p50 / p95** | Wall-clock time per attempt. |
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
