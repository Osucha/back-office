/**
 * ADR 0063: the "Deliver changes" action (`DELIVER_CHANGES`): commits what the agents changed in the run directory,
 * pushes a branch and optionally opens a merge request. Its config form (translated labels, the merge request
 * fields only while a merge request is opened), its repositories and the summary on the node card. Pure; tested in
 * deliver-changes.spec.ts.
 */
import type { JsonSchema } from './schema-form';

export const DELIVER_CHANGES = 'DELIVER_CHANGES';
/** Config key of the repositories to deliver (keys of project repositories; empty = every changed one). */
export const DELIVER_REPOSITORIES_KEY = 'repositories';
/** The run branch when the action sets none (ADR 0031). */
export const DEFAULT_BRANCH_TEMPLATE = 'feature/{{issue.key | lower}}'; // i18n-ignore

const MERGE_REQUEST_KEYS = [
  'mergeRequestTitleTemplate',
  'mergeRequestDescriptionTemplate',
  'mergeRequestDraft',
];

type Config = Record<string, unknown> | null | undefined;

/** The known keys with translated labels and help, in form order. */
function knownProperties(): Record<string, JsonSchema> {
  return {
    branchTemplate: {
      type: 'string',
      title: $localize`:@@workflows.deliver.branchTemplate:Branch`,
      description: $localize`:@@workflows.deliver.branchTemplate.help:Name of the branch pushed for the run (template). Never the default branch of the repository. Empty: ${DEFAULT_BRANCH_TEMPLATE}:branch:.`,
      'x-multiline': false,
      'x-order': 0,
    },
    commitSubjectTemplate: {
      type: 'string',
      title: $localize`:@@workflows.deliver.commitSubject:Commit subject`,
      description: $localize`:@@workflows.deliver.commitSubject.help:First line of the commit message (template). Empty: the issue key and summary.`,
      'x-multiline': false,
      'x-order': 1,
    },
    mergeRequest: {
      type: 'boolean',
      default: true,
      title: $localize`:@@workflows.deliver.mergeRequest:Open a merge request`,
      description: $localize`:@@workflows.deliver.mergeRequest.help:Opens a merge request from the branch to the default branch. Back Office never merges it.`,
      'x-order': 2,
    },
    mergeRequestTitleTemplate: {
      type: 'string',
      title: $localize`:@@workflows.deliver.mrTitle:Merge request title`,
      description: $localize`:@@workflows.deliver.mrTitle.help:Template. Empty: ${'{{issue.key}}: {{issue.summary}}'}:example:.`,
      'x-multiline': false,
      'x-order': 3,
    },
    mergeRequestDescriptionTemplate: {
      type: 'string',
      title: $localize`:@@workflows.deliver.mrDescription:Merge request description`,
      description: $localize`:@@workflows.deliver.mrDescription.help:Template; may read the results of earlier steps, e.g. ${'{{artifacts.review}}'}:example:.`,
      'x-multiline': true,
      'x-order': 4,
    },
    mergeRequestDraft: {
      type: 'boolean',
      default: false,
      title: $localize`:@@workflows.deliver.mrDraft:Draft`,
      description: $localize`:@@workflows.deliver.mrDraft.help:Opens the merge request as a draft.`,
      'x-order': 5,
    },
  };
}

/** Whether the action opens a merge request (default yes). */
export function opensMergeRequest(config: Config): boolean {
  return config?.['mergeRequest'] !== false;
}

/**
 * The form of the action's config: the known keys translated (defaults and unknown keys from the server's schema,
 * when it has one), without the repositories (their own select) and without the merge request fields while no
 * merge request is opened. Never null: the form renders even when the palette has no schema.
 */
export function deliverChangesSchema(
  server: JsonSchema | null | undefined,
  config: Config,
): JsonSchema {
  const known = knownProperties();
  const serverProps = server?.properties ?? {};
  const properties: Record<string, JsonSchema> = {};
  for (const [k, p] of Object.entries(known)) {
    const s = serverProps[k];
    properties[k] = s?.default !== undefined ? { ...p, default: s.default } : p;
  }
  let order = Object.keys(known).length;
  for (const [k, p] of Object.entries(serverProps))
    if (!(k in known)) properties[k] = { ...p, 'x-order': p['x-order'] ?? order++ };
  delete properties[DELIVER_REPOSITORIES_KEY];
  if (!opensMergeRequest(config)) for (const k of MERGE_REQUEST_KEYS) delete properties[k];
  return { type: 'object', properties };
}

/** The repositories the action delivers; empty = every project repository of the sources with changes. */
export function deliverRepositories(config: Config): string[] {
  const raw = config?.[DELIVER_REPOSITORIES_KEY];
  if (!Array.isArray(raw)) return [];
  return raw
    .map((r) =>
      typeof r === 'string'
        ? r
        : r && typeof r === 'object'
          ? String((r as { key?: unknown }).key ?? '')
          : '',
    )
    .filter((k) => !!k);
}

/** The config with the repositories set (an empty list removes the key). */
export function withDeliverRepositories(
  config: Config,
  keys: readonly string[],
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (keys.length) next[DELIVER_REPOSITORIES_KEY] = [...keys];
  else delete next[DELIVER_REPOSITORIES_KEY];
  return next;
}

/**
 * The config shown in the schema form: without the repositories and, while no merge request is opened, without
 * the merge request fields (kept in the node, see `withFormConfig`).
 */
export function deliverFormConfig(config: Config): Record<string, unknown> {
  const rest: Record<string, unknown> = { ...(config ?? {}) };
  delete rest[DELIVER_REPOSITORIES_KEY];
  if (!opensMergeRequest(config)) for (const k of MERGE_REQUEST_KEYS) delete rest[k];
  return rest;
}

/** A change of the schema form, keeping the keys the form does not show. */
export function withFormConfig(
  current: Config,
  form: Record<string, unknown>,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...form };
  const kept = [DELIVER_REPOSITORIES_KEY, ...(opensMergeRequest(form) ? [] : MERGE_REQUEST_KEYS)];
  for (const k of kept) if (current && k in current && !(k in next)) next[k] = current[k];
  return next;
}

export interface DeliverySummary {
  /** The branch template (the default one when the action sets none). */
  branch: string;
  mergeRequest: 'OFF' | 'ON' | 'DRAFT';
  repositories: string[];
}

/** What the node card shows: the branch and whether (and how) a merge request is opened. */
export function deliverySummary(config: Config): DeliverySummary {
  const branch = config?.['branchTemplate'];
  return {
    branch: typeof branch === 'string' && branch.trim() ? branch.trim() : DEFAULT_BRANCH_TEMPLATE,
    mergeRequest: !opensMergeRequest(config)
      ? 'OFF'
      : config?.['mergeRequestDraft'] === true
        ? 'DRAFT'
        : 'ON',
    repositories: deliverRepositories(config),
  };
}

/** The merge request chip of the node card: "MR", "MR (draft)" or "no MR". */
export function mergeRequestLabel(mode: DeliverySummary['mergeRequest']): string {
  switch (mode) {
    case 'OFF':
      return $localize`:@@workflows.deliver.card.noMr:no MR`;
    case 'DRAFT':
      return $localize`:@@workflows.deliver.card.draftMr:MR (draft)`;
    default:
      return $localize`:@@workflows.deliver.card.mr:MR`;
  }
}

/** Tooltip of the node card: the branch, the merge request and the repositories. */
export function deliverySummaryTitle(s: DeliverySummary): string {
  const repositories = s.repositories.length
    ? s.repositories.join(', ')
    : $localize`:@@workflows.deliver.card.allRepositories:every changed repository`;
  return $localize`:@@workflows.deliver.card.title:Branch ${s.branch}:branch:, ${mergeRequestLabel(s.mergeRequest)}:mr:, ${repositories}:repositories:`;
}
