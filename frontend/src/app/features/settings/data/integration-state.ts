/**
 * Integration status in the Relay vocabulary (pure; tested in integration-state.spec.ts):
 * configured -> done, not configured -> idle, a failed connection test -> blocked, a passing test
 * -> done "connected". The word always travels with the colour.
 */
import type {
  IntegrationName,
  IntegrationsStatus,
  IntegrationTestResult,
} from '../../../core/api/types';
import type { Baton } from '../../../core/ui/relay/baton';

export function integrationBaton(
  configured: boolean,
  result?: IntegrationTestResult | null,
  testing = false,
): Baton {
  if (testing)
    return { holder: 'agent', label: $localize`:@@settings.state.testing:testing`, live: true };
  if (result)
    return result.ok
      ? { holder: 'done', label: $localize`:@@settings.state.connected:connected` }
      : { holder: 'blocked', label: $localize`:@@settings.state.testFailed:test failed` };
  return configured
    ? { holder: 'done', label: $localize`:@@settings.state.configured:configured` }
    : { holder: 'idle', label: $localize`:@@settings.state.notConfigured:not configured` };
}

export interface IntegrationCard {
  name: IntegrationName;
  title: string;
  icon: string;
  what: string;
  configured: boolean;
  lines: { label: string; value: string }[];
  testable: boolean;
  /** Why the test is unavailable (shown instead of the button). */
  untestable?: string;
  /** A page that manages the integration further (e.g. the runner fleet). */
  link?: { label: string; path: string };
}

/**
 * `backoffice.runner.mode` as `/settings/integrations` reports it. FLEET (the default since the
 * runner fleet, ADR 0024) is not in the generated enum yet, so the value is read as a string.
 */
export type RunnerMode = 'FLEET' | 'KUBERNETES' | 'DOCKER' | 'DISABLED';

export function runnerModeOf(s: IntegrationsStatus): RunnerMode | string {
  return ((s.runner?.mode as string | undefined) ?? 'DISABLED').toUpperCase();
}

function runnerCard(s: IntegrationsStatus): IntegrationCard {
  const mode = runnerModeOf(s);
  const modeLabel = $localize`:@@settings.runner.mode:Mode`;
  const base = {
    name: 'kubernetes' as IntegrationName,
    title: $localize`:@@settings.runner.title:Agent runner`,
    icon: 'cloud-server',
  };
  if (mode === 'FLEET')
    return {
      ...base,
      what: $localize`:@@settings.runner.fleetWhat:Runner fleet: agent jobs run in sandboxes of registered runner pools in your clusters.`,
      configured: true,
      lines: [
        { label: modeLabel, value: $localize`:@@settings.runner.fleetMode:Runner fleet` },
        {
          label: $localize`:@@settings.runner.runnerImage:Runner image`,
          value: s.runner?.image || '-',
        },
      ],
      // The connection test checks that at least one runner pool exists.
      testable: true,
      link: {
        label: $localize`:@@settings.runner.openFleet:Open runner fleet`,
        path: '/admin/fleet',
      },
    };
  return {
    ...base,
    what: $localize`:@@settings.runner.what:Where coding agents run: one isolated job per task.`,
    configured: mode !== 'DISABLED',
    lines: [
      {
        label: modeLabel,
        // Kubernetes and Docker are product names; only "Disabled" is a word.
        value:
          mode === 'DISABLED'
            ? $localize`:@@settings.runner.modeDisabled:Disabled`
            : mode.charAt(0) + mode.slice(1).toLowerCase(),
      },
      { label: $localize`:@@settings.runner.image:Image`, value: s.runner?.image ?? '-' },
      {
        label: $localize`:@@settings.runner.namespace:Namespace`,
        value: s.runner?.namespace ?? '-',
      },
    ],
    testable: mode === 'KUBERNETES',
    untestable:
      mode === 'DOCKER'
        ? $localize`:@@settings.runner.dockerUntestable:The Docker runner is checked when a job starts.`
        : mode === 'DISABLED'
          ? $localize`:@@settings.runner.disabledUntestable:Enable a runner mode on the server first.`
          : undefined,
  };
}

export function integrationCards(s: IntegrationsStatus): IntegrationCard[] {
  return [
    {
      name: 'jira',
      title: 'Jira',
      icon: 'schedule',
      what: $localize`:@@settings.jira.what:Issues that start workflows and receive their status.`,
      configured: !!s.jira?.configured,
      lines: [
        {
          label: $localize`:@@settings.jira.connections:Connections`,
          value: String(s.jira?.platformConnections ?? 0),
        },
        ...(s.jira?.legacy || s.jira?.url
          ? [
              {
                label: $localize`:@@settings.jira.legacyUrl:Legacy URL`,
                value: s.jira?.url ?? '-',
              },
            ]
          : []),
      ],
      testable: true,
      link: {
        label: $localize`:@@settings.jira.manageConnections:Manage connections`,
        path: '/admin/connections',
      },
    },
    runnerCard(s),
    {
      name: 'langfuse',
      title: 'Langfuse', // i18n-ignore (product name)
      icon: 'fund',
      what: $localize`:@@settings.langfuse.what:Traces of every LLM call, for debugging prompts and costs.`,
      configured: !!s.langfuse?.enabled,
      lines: [{ label: 'URL', value: s.langfuse?.url ?? '-' }],
      testable: true,
    },
  ];
}
