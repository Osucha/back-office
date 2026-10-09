// i18n-ignore-file: test fixture mirroring the workflows the backend seeds (server data).
/**
 * The three workflows the backend seeds into every project
 * (backend/projects-core/.../domain/service/DefaultWorkflows.kt), in the JSON shape of
 * `GET /workflows/{id}`, and the palette ports of the seeded Task Library. Used by the regression
 * tests that the designer opens them without errors and lays them out readably. Keep in sync with
 * the Kotlin factories (positions included).
 */
import type {
  WorkflowDefinitionInput,
  WorkflowEdge,
  WorkflowNode,
  WorkflowPalette,
} from '../../../core/api/types';

const overrides = { addSkills: [], removeSkills: [], config: {} };
const spec = [
  { id: 'acceptance-criteria', text: 'Acceptance criteria are testable', required: true },
  {
    id: 'open-questions',
    text: 'Open questions are answered or explicitly deferred with an owner',
    required: true,
  },
  { id: 'scope-stated', text: 'Scope and out-of-scope are stated', required: true },
];
const mergeDone = [
  { id: 'tests-green', text: 'Tests are green in CI', required: true },
  {
    id: 'review-findings-addressed',
    text: 'Review findings are addressed or answered',
    required: true,
  },
  { id: 'spec-updated', text: 'Specification and docs are updated', required: true },
];
const owner = { projectRoles: [], users: [], accountable: 'owner', forbidSelfApproval: false };
const fourEyes = { ...owner, forbidSelfApproval: true };

type P = [number, number];
const at = ([x, y]: P) => ({ x, y });
const start = (p: P): WorkflowNode => ({
  id: 'start',
  kind: 'START',
  name: 'Start',
  position: at(p),
  maxVisits: 3,
});
const task = (id: string, name: string, key: string, p: P): WorkflowNode => ({
  id,
  kind: 'TASK',
  name,
  position: at(p),
  maxVisits: 3,
  task: { templateKey: key, overrides },
});
const end = (id: string, name: string, outcome: 'SUCCESS' | 'FAILURE', p: P): WorkflowNode => ({
  id,
  kind: 'END',
  name,
  position: at(p),
  maxVisits: 3,
  end: { outcome },
});
const reviewOk = (id: string, p: P): WorkflowNode => ({
  id,
  kind: 'DECISION',
  name: 'Review ok?',
  position: at(p),
  maxVisits: 3,
  decision: {
    branches: [
      {
        id: 'approved',
        label: 'no changes requested',
        expression: "review.verdict != 'REQUEST_CHANGES'",
      },
    ],
    defaultBranch: 'changes',
  },
});
const gate = (
  id: string,
  name: string,
  question: string,
  showArtifacts: string[],
  approvers: typeof owner,
  checklist: typeof spec,
  p: P,
): WorkflowNode => ({
  id,
  kind: 'HUMAN_GATE',
  name,
  position: at(p),
  maxVisits: 3,
  gate: {
    question,
    options: ['approve', 'revise', 'reject'],
    showArtifacts,
    timeoutSeconds: null,
    approvers,
    checklist,
  },
});
const ci = (p: P): WorkflowNode => ({
  id: 'ci-tests',
  kind: 'WAIT_FOR_CI',
  name: 'Wait for CI tests',
  position: at(p),
  maxVisits: 3,
  ci: { signal: 'tests', timeoutSeconds: 7200 },
});
const edges = (list: Array<[string, string, string, string?]>): WorkflowEdge[] =>
  list.map(([from, fromPort, to, label], i) => ({
    id: `e${i + 1}`,
    from,
    fromPort,
    to,
    ...(label ? { label } : {}),
  }));
const merge =
  '{{issue.key}} passed AI review (verdict {{review.verdict}}, score {{review.score}}). Merge request: {{mr.url}}. Merge?';

export function seededIssueToReviewedMr(): WorkflowDefinitionInput {
  return {
    name: 'Issue to reviewed MR',
    description:
      'Jira issue -> Spec Kit specification -> clarification -> plan -> tasks -> implementation (MR via push options) -> CI tests -> AI review -> merge gate -> issue transition.',
    nodes: [
      start([40, 40]),
      task('fetch-issue', 'Fetch issue', 'fetch-issue', [316, 40]),
      task('specify', 'Specify', 'spec-kit-specify', [652, 40]),
      task('clarify', 'Clarify spec', 'clarify-spec', [988, 40]),
      {
        id: 'confident',
        kind: 'DECISION',
        name: 'Spec confident?',
        position: at([1324, 40]),
        maxVisits: 3,
        decision: {
          branches: [{ id: 'yes', label: 'confidence >= 0.8', expression: 'confidence >= 0.8' }],
          defaultBranch: 'no',
        },
      },
      gate(
        'spec-gate',
        'Spec ok?',
        'Spec for {{issue.key}} has open questions (confidence {{confidence}}). Approve, revise or reject?',
        ['spec', 'clarifications'],
        owner,
        spec,
        [1660, 262],
      ),
      task('plan', 'Plan', 'spec-kit-plan', [1996, 40]),
      task('tasks', 'Tasks', 'spec-kit-tasks', [2332, 40]),
      task('implement', 'Implement', 'implement-opencode', [2668, 40]),
      ci([3004, 40]),
      end('tests-failed', 'Tests failed', 'FAILURE', [3370, 262]),
      task('code-review', 'AI code review', 'code-review', [3340, 40]),
      reviewOk('review-ok', [3676, 40]),
      gate('merge-gate', 'Merge?', merge, ['review', 'diff'], fourEyes, mergeDone, [4012, 40]),
      task('transition', 'Move issue to In Review', 'transition-issue', [4348, 40]),
      end('done', 'Done', 'SUCCESS', [4684, 40]),
    ],
    edges: edges([
      ['start', 'out', 'fetch-issue'],
      ['fetch-issue', 'success', 'specify'],
      ['specify', 'success', 'clarify'],
      ['clarify', 'success', 'confident'],
      ['confident', 'yes', 'plan', 'confident'],
      ['confident', 'no', 'spec-gate', 'needs a look'],
      ['spec-gate', 'approve', 'plan'],
      ['spec-gate', 'revise', 'specify', 'revise'],
      ['plan', 'success', 'tasks'],
      ['tasks', 'success', 'implement'],
      ['implement', 'success', 'ci-tests'],
      ['ci-tests', 'success', 'code-review'],
      ['ci-tests', 'fail', 'tests-failed'],
      ['code-review', 'success', 'review-ok'],
      ['review-ok', 'approved', 'merge-gate'],
      ['review-ok', 'changes', 'implement', 'rework'],
      ['merge-gate', 'approve', 'transition'],
      ['merge-gate', 'revise', 'implement', 'revise'],
      ['transition', 'success', 'done'],
    ]),
    triggers: [{ type: 'MANUAL' }, { type: 'API' }],
    enabled: true,
    canvas: null,
  };
}

export function seededCiCodeReview(): WorkflowDefinitionInput {
  return {
    name: 'CI code review',
    description:
      'Reviews the diff uploaded by the CI job; the END outcome decides the CI gate result. Registered as entrypoint code-review.',
    nodes: [
      start([40, 40]),
      task('code-review', 'AI code review', 'code-review', [316, 40]),
      reviewOk('verdict', [652, 40]),
      end('passed', 'Passed', 'SUCCESS', [988, 40]),
      end('failed', 'Changes requested', 'FAILURE', [988, 244]),
    ],
    edges: edges([
      ['start', 'out', 'code-review'],
      ['code-review', 'success', 'verdict'],
      ['verdict', 'approved', 'passed'],
      ['verdict', 'changes', 'failed'],
    ]),
    triggers: [{ type: 'CI' }, { type: 'MANUAL' }],
    enabled: true,
    canvas: null,
  };
}

export function seededChangeRequest(): WorkflowDefinitionInput {
  return {
    name: 'Change request (delta)',
    description:
      'Delta change: impact analysis (approved by the owner) -> user story -> task list with verification per task (approved) -> implementation -> CI tests -> AI review -> merge gate (four-eyes).',
    nodes: [
      start([40, 40]),
      task('impact-analysis', 'Impact analysis', 'impact-analysis', [316, 40]),
      gate(
        'impact-gate',
        'Approve impact analysis?',
        'Impact analysis for {{issue.key}} is ready. Approve the analysis, ask for a revision or reject the change?',
        ['impact-analysis'],
        owner,
        [
          { id: 'affected-artefacts', text: 'Affected artefacts are listed', required: true },
          { id: 'estimate-given', text: 'An estimate is given', required: true },
          { id: 'options-compared', text: 'Options are compared', required: true },
        ],
        [652, 40],
      ),
      task('user-story', 'Write user story', 'write-user-story', [988, 40]),
      task('plan-tasks', 'Plan task list', 'plan-task-list', [1324, 40]),
      gate(
        'task-list-gate',
        'Approve task list?',
        'Task list for {{issue.key}} is ready. Approve, revise or reject?',
        ['user-story', 'task-list'],
        owner,
        [
          {
            id: 'task-verification',
            text: 'Every task has verification and test_command',
            required: true,
          },
          {
            id: 'final-quality-task',
            text: 'The last task is reformat, lint, and test',
            required: true,
          },
        ],
        [1660, 40],
      ),
      task('implement', 'Implement task list', 'implement-task-list', [1996, 40]),
      ci([2332, 40]),
      end('tests-failed', 'Tests failed', 'FAILURE', [2698, 262]),
      task('code-review', 'AI code review', 'code-review', [2668, 40]),
      reviewOk('verdict', [3004, 40]),
      gate('merge-gate', 'Merge?', merge, ['review', 'diff'], fourEyes, mergeDone, [3340, 40]),
      end('done', 'Done', 'SUCCESS', [3676, 40]),
    ],
    edges: edges([
      ['start', 'out', 'impact-analysis'],
      ['impact-analysis', 'success', 'impact-gate'],
      ['impact-gate', 'approve', 'user-story'],
      ['impact-gate', 'revise', 'impact-analysis', 'revise'],
      ['user-story', 'success', 'plan-tasks'],
      ['plan-tasks', 'success', 'task-list-gate'],
      ['task-list-gate', 'approve', 'implement'],
      ['task-list-gate', 'revise', 'plan-tasks', 'revise'],
      ['implement', 'success', 'ci-tests'],
      ['ci-tests', 'success', 'code-review'],
      ['ci-tests', 'fail', 'tests-failed'],
      ['code-review', 'success', 'verdict'],
      ['verdict', 'approved', 'merge-gate'],
      ['verdict', 'changes', 'implement', 'rework'],
      ['merge-gate', 'approve', 'done'],
      ['merge-gate', 'revise', 'implement', 'revise'],
    ]),
    triggers: [{ type: 'MANUAL' }, { type: 'API' }],
    enabled: true,
    canvas: null,
  };
}

export const SEEDED_WORKFLOWS = [seededIssueToReviewedMr, seededCiCodeReview, seededChangeRequest];

const TASK_KEYS = [
  'analyse-issue',
  'clarify-spec',
  'code-review',
  'fetch-issue',
  'impact-analysis',
  'implement-claude-code',
  'implement-opencode',
  'implement-task-list',
  'notify-jira',
  'plan-task-list',
  'review-against-spec',
  'spec-kit-plan',
  'spec-kit-specify',
  'spec-kit-tasks',
  'transition-issue',
  'write-adr',
  'write-use-case',
  'write-user-story',
];

/** The palette of a seeded project (`GET /workflows/palette`), reduced to what validation uses. */
export const SEEDED_PALETTE: WorkflowPalette = {
  tasks: TASK_KEYS.map((key) => ({
    key,
    name: key,
    ports: ['success', 'failure'],
    interfaces: ['x'],
  })),
  controlNodes: [
    { kind: 'START', ports: ['out'] },
    { kind: 'END', ports: [] },
    { kind: 'DECISION', ports: ['<branch id>'] },
    { kind: 'HUMAN_GATE', ports: ['approve', 'revise', 'reject', 'timeout'] },
    { kind: 'WAIT_FOR_CI', ports: ['success', 'fail', 'timeout'] },
    { kind: 'NOTE', ports: [] },
  ],
};
