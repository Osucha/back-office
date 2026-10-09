/**
 * The ONE map from screens to the manual (docs/manual/manual.json). Pages and panels name a topic
 * (`<bo-help-link topic="runs" />`, `<bo-page-header help="runs">`); the top bar and `F1` find the
 * topic of the current URL with `helpTopicForUrl()`. `help-map.spec.ts` checks that every chapter
 * and anchor here exists in the manifest, so a renamed anchor fails the build.
 */
import type { HelpRef } from './manual';

export interface HelpTopic extends HelpRef {
  /** What the help is about: "Help: <label>" is the accessible name of the help button. */
  label: string;
}

const t = (chapter: string, anchor: string | null, label: string): HelpTopic => ({
  chapter,
  anchor,
  label,
});

export const HELP_TOPICS = {
  // ---- using Back Office
  home: t('your-turn', 'home', $localize`:@@core.help.topic.home:Home`),
  inbox: t('your-turn', 'inbox', $localize`:@@core.help.topic.inbox:Your turn`),
  gates: t('your-turn', 'gates', $localize`:@@core.help.topic.gates:Gates`),
  'gates.assign': t(
    'your-turn',
    'assign-steps',
    $localize`:@@core.help.topic.gates.assign:Assigning a step`,
  ),
  firstSteps: t(
    'getting-started',
    'first-steps',
    $localize`:@@core.help.topic.firstSteps:First steps`,
  ),
  projects: t('projects', 'create-project', $localize`:@@core.help.topic.projects:Projects`),
  'project.workflows': t(
    'workflows',
    'designer',
    $localize`:@@core.help.topic.project.workflows:Workflows`,
  ),
  'project.runs': t(
    'runs',
    'run-list',
    $localize`:@@core.help.topic.project.runs:Runs of the project`,
  ),
  'project.ci': t(
    'integrations-gitlab-ci',
    'ci-token',
    $localize`:@@core.help.topic.project.ci:CI tokens`,
  ),
  'project.ciVariables': t(
    'integrations-gitlab-ci',
    'ci-variables',
    $localize`:@@core.help.topic.project.ciVariables:CI/CD variables`,
  ),
  'project.repositories': t(
    'projects',
    'repositories',
    $localize`:@@core.help.topic.project.repositories:Project repositories`,
  ),
  'project.noRepository': t(
    'projects',
    'no-repository',
    $localize`:@@core.help.topic.project.noRepository:Projects without a repository`,
  ),
  'project.members': t(
    'projects',
    'members',
    $localize`:@@core.help.topic.project.members:Project members`,
  ),
  'project.sandbox': t(
    'projects',
    'sandbox-policy',
    $localize`:@@core.help.topic.project.sandbox:Sandbox policy`,
  ),
  'project.budget': t(
    'projects',
    'project-budget',
    $localize`:@@core.help.topic.project.budget:Project budget`,
  ),
  'project.settings': t(
    'projects',
    'create-project',
    $localize`:@@core.help.topic.project.settings:Project settings`,
  ),
  'project.jira': t(
    'integrations-jira',
    'jira-config',
    $localize`:@@core.help.topic.project.jira:Jira for this project`,
  ),
  'project.connections': t(
    'integrations-jira',
    'jira-connections',
    $localize`:@@core.help.topic.project.connections:Project connections`,
  ),
  'connections.leastPrivilege': t(
    'integrations-jira',
    'jira-least-privilege',
    $localize`:@@core.help.topic.connections.leastPrivilege:Least privilege for Jira`,
  ),
  'jira.intake': t(
    'integrations-jira',
    'jira-intake',
    $localize`:@@core.help.topic.jira.intake:Jira intake watcher`,
  ),
  'jira.writeApproval': t(
    'integrations-jira',
    'jira-write-approval',
    $localize`:@@core.help.topic.jira.writeApproval:Approval before writing to Jira`,
  ),
  'connections.expiry': t(
    'integrations-jira',
    'jira-token-expiry',
    $localize`:@@core.help.topic.connections.expiry:Token expiry and rotation`,
  ),
  'connections.personal': t(
    'integrations-jira',
    'jira-personal-connections',
    $localize`:@@core.help.topic.connections.personal:Personal connections`,
  ),
  'connections.actAs': t(
    'integrations-jira',
    'jira-act-as',
    $localize`:@@core.help.topic.connections.actAs:Act as (which Jira identity a step uses)`,
  ),
  'connections.scoped': t(
    'integrations-jira',
    'jira-scoped-token',
    $localize`:@@core.help.topic.connections.scoped:Scoped API tokens`,
  ),
  'project.jiraConnections': t(
    'integrations-jira',
    'jira-system-connection',
    $localize`:@@core.help.topic.project.jiraConnections:System connection of a project`,
  ),
  'project.specKit': t('projects', 'spec-kit', $localize`:@@core.help.topic.project.specKit:Spec Kit`),
  'gates.writeConfirmations': t(
    'your-turn',
    'write-confirmations',
    $localize`:@@core.help.topic.gates.writeConfirmations:Confirming external writes`,
  ),
  'designer.writeConfirmations': t(
    'workflows',
    'write-confirmations',
    $localize`:@@core.help.topic.designer.writeConfirmations:Confirmations before external writes`,
  ),
  designer: t('workflows', 'designer', $localize`:@@core.help.topic.designer:Workflow designer`),
  'step.repositories': t(
    'workflows',
    'step-repositories',
    $localize`:@@core.help.topic.step.repositories:Repositories of a step`,
  ),
  runs: t('runs', 'run-list', $localize`:@@core.help.topic.runs:Runs`),
  run: t('runs', 'run-detail', $localize`:@@core.help.topic.run:Run`),
  'run.repositories': t(
    'runs',
    'repository-results',
    $localize`:@@core.help.topic.run.repositories:Repository results`,
  ),
  provenance: t('runs', 'provenance', $localize`:@@core.help.topic.provenance:Provenance`),
  insights: t('costs-insights', 'insights', $localize`:@@core.help.topic.insights:Insights`),
  costs: t('costs-insights', 'costs', $localize`:@@core.help.topic.costs:Costs`),
  ci: t('integrations-gitlab-ci', 'ci-overview', $localize`:@@core.help.topic.ci:CI executions`),
  templates: t('task-library', 'templates', $localize`:@@core.help.topic.templates:Task templates`),
  interfaces: t(
    'task-library',
    'interfaces',
    $localize`:@@core.help.topic.interfaces:Task interfaces`,
  ),
  skills: t('task-library', 'skills', $localize`:@@core.help.topic.skills:Skills`),
  models: t('evaluation', 'models', $localize`:@@core.help.topic.models:Models`),
  suites: t('evaluation', 'suites', $localize`:@@core.help.topic.suites:Task suites`),
  benchmarks: t('evaluation', 'benchmarks', $localize`:@@core.help.topic.benchmarks:Benchmarks`),
  // ---- settings and account
  settings: t('getting-started', 'first-steps', $localize`:@@core.help.topic.settings:Settings`),
  'settings.jira': t(
    'integrations-jira',
    'jira-token',
    $localize`:@@core.help.topic.settings.jira:Jira integration`,
  ),
  'settings.llm': t(
    'integrations-llm',
    'llm-overview',
    $localize`:@@core.help.topic.settings.llm:LLM providers`,
  ),
  'settings.runner': t(
    'agent-execution',
    'lifecycle',
    $localize`:@@core.help.topic.settings.runner:Agent runner`,
  ),
  'settings.ciScript': t(
    'integrations-gitlab-ci',
    'ci-template',
    $localize`:@@core.help.topic.settings.ciScript:CI client script`,
  ),
  'settings.observability': t(
    'integrations-other',
    'observability',
    $localize`:@@core.help.topic.settings.observability:Langfuse`,
  ),
  account: t('account', 'profile', $localize`:@@core.help.topic.account:My account`),
  'account.tokens': t(
    'integrations-ide',
    'pat',
    $localize`:@@core.help.topic.account.tokens:Personal access tokens`,
  ),
  'account.ide': t(
    'integrations-ide',
    'claude-code',
    $localize`:@@core.help.topic.account.ide:Connect your IDE`,
  ),
  'account.mfa': t(
    'account',
    'mfa',
    $localize`:@@core.help.topic.account.mfa:Two-factor authentication`,
  ),
  'account.notifications': t(
    'account',
    'notifications',
    $localize`:@@core.help.topic.account.notifications:Desktop notifications`,
  ),
  // ---- administration and the fleet
  'admin.users': t('administration', 'users', $localize`:@@core.help.topic.admin.users:Users`),
  'admin.groups': t('administration', 'groups', $localize`:@@core.help.topic.admin.groups:Groups`),
  'admin.roles': t(
    'administration',
    'roles-grants',
    $localize`:@@core.help.topic.admin.roles:Roles`,
  ),
  'admin.grants': t(
    'administration',
    'roles-grants',
    $localize`:@@core.help.topic.admin.grants:Grants`,
  ),
  'admin.projectManagers': t(
    'administration',
    'project-managers',
    $localize`:@@core.help.topic.admin.projectManagers:Project managers`,
  ),
  'admin.settings': t(
    'administration',
    'platform-settings',
    $localize`:@@core.help.topic.admin.settings:Platform settings`,
  ),
  'admin.connections': t(
    'integrations-jira',
    'jira-connections',
    $localize`:@@core.help.topic.admin.connections:Connections`,
  ),
  'admin.audit': t('administration', 'audit', $localize`:@@core.help.topic.admin.audit:Audit log`),
  'admin.operations': t(
    'administration',
    'operations',
    $localize`:@@core.help.topic.admin.operations:Operations`,
  ),
  'fleet.pools': t('runner-fleet', 'pools', $localize`:@@core.help.topic.fleet.pools:Runner pools`),
  'fleet.runners': t(
    'runner-fleet',
    'add-runner',
    $localize`:@@core.help.topic.fleet.runners:Runners`,
  ),
  'fleet.jobs': t(
    'runner-fleet',
    'agent-jobs',
    $localize`:@@core.help.topic.fleet.jobs:Agent jobs`,
  ),
  'fleet.addRunner': t(
    'runner-fleet',
    'add-runner',
    $localize`:@@core.help.topic.fleet.addRunner:Add a runner`,
  ),
  'fleet.poolEditor': t(
    'runner-fleet',
    'sandbox-policies',
    $localize`:@@core.help.topic.fleet.poolEditor:Pool policy ceiling`,
  ),
  'fleet.gitAccess': t(
    'integrations-git',
    'git-least-privilege',
    $localize`:@@core.help.topic.fleet.gitAccess:Git access for agents`,
  ),
} as const satisfies Record<string, HelpTopic>;

export type HelpKey = keyof typeof HELP_TOPICS;

export function helpTopic(key: HelpKey): HelpTopic {
  return HELP_TOPICS[key];
}

/** Project detail `?tab=` -> topic (no tab = Workflows). */
const PROJECT_TABS: Record<string, HelpKey> = {
  workflows: 'project.workflows',
  runs: 'project.runs',
  ci: 'project.ci',
  members: 'project.members',
  connections: 'project.connections',
  sandbox: 'project.sandbox',
  budget: 'project.budget',
  settings: 'project.settings',
};

/** Path patterns, first match wins. Paths are matched without query and fragment. */
const ROUTES: Array<[RegExp, HelpKey | ((query: URLSearchParams) => HelpKey)]> = [
  [/^\/(home|dashboard)?$/, 'home'],
  [/^\/inbox(\/[^/]+)?$/, 'inbox'],
  [/^\/projects$/, 'projects'],
  [/^\/projects\/[^/]+\/workflows\/new$/, 'designer'],
  [/^\/projects\/[^/]+$/, (q) => PROJECT_TABS[q.get('tab') ?? ''] ?? 'project.workflows'],
  [/^\/workflows\/[^/]+\/edit$/, 'designer'],
  [/^\/runs$/, 'runs'],
  [/^\/runs\/[^/]+$/, (q) => (q.get('tab') === 'provenance' ? 'provenance' : 'run')],
  [/^\/insights$/, 'insights'],
  [/^\/costs$/, 'costs'],
  [/^\/ci(\/[^/]+)?$/, 'ci'],
  [/^\/library(\/templates)?$/, 'templates'],
  [/^\/library\/interfaces$/, 'interfaces'],
  [/^\/library\/skills$/, 'skills'],
  [/^\/models$/, 'models'],
  [/^\/evaluation(\/suites(\/[^/]+)?)?$/, 'suites'],
  [/^\/evaluation\/benchmarks(\/[^/]+)?$/, 'benchmarks'],
  [/^\/settings$/, 'settings'],
  [
    /^\/account$/,
    (q) =>
      q.get('section') === 'ide'
        ? 'account.ide'
        : q.get('section') === 'tokens'
          ? 'account.tokens'
          : q.get('section') === 'security'
            ? 'account.mfa'
            : q.get('section') === 'notifications'
              ? 'account.notifications'
              : 'account',
  ],
  [/^\/admin(\/users(\/[^/]+)?)?$/, 'admin.users'],
  [/^\/admin\/groups(\/[^/]+)?$/, 'admin.groups'],
  [/^\/admin\/roles(\/[^/]+)?$/, 'admin.roles'],
  [/^\/admin\/grants$/, 'admin.grants'],
  [/^\/admin\/settings$/, 'admin.settings'],
  [/^\/admin\/audit$/, 'admin.audit'],
  [/^\/admin\/connections$/, 'admin.connections'],
  [/^\/admin\/operations$/, 'admin.operations'],
  [/^\/admin\/fleet(\/pools)?$/, (q) => (q.get('addRunner') ? 'fleet.addRunner' : 'fleet.pools')],
  [/^\/admin\/fleet\/pools\/[^/]+$/, 'fleet.poolEditor'],
  [/^\/admin\/fleet\/runners$/, 'fleet.runners'],
  [/^\/admin\/fleet\/jobs$/, 'fleet.jobs'],
];

/** The help topic of an app URL (`/projects/1?tab=ci` -> `project.ci`), or null (no page help). */
export function helpTopicForUrl(url: string): HelpKey | null {
  const [beforeHash] = url.split('#');
  const [path, query = ''] = beforeHash.split('?');
  const clean = path.length > 1 ? path.replace(/\/+$/, '') : path;
  const q = new URLSearchParams(query);
  for (const [re, key] of ROUTES) if (re.test(clean)) return typeof key === 'string' ? key : key(q);
  return null;
}
