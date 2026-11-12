/**
 * ADR 0063: the config keys of a step and of a template. Delivering (commit, branch, push, merge request) is the
 * "Deliver changes" action, the repositories and the setup command belong to the workflow, Spec Kit is gone and
 * `collectArtifacts` is the template field `outputFiles`. What remains of the coding agent keys gets translated
 * labels and help here. Pure; tested in step-config-keys.spec.ts.
 */
import type { JsonSchema } from './schema-form';

/** Step / template config keys that left the step (ADR 0063); never shown in a config form, kept as they are. */
export const REMOVED_STEP_KEYS = [
  'push',
  'branchTemplate',
  'mergeRequest',
  'mergeRequestTitleTemplate',
  'mergeRequestDescriptionTemplate',
  'mergeRequestDraft',
  'commitSubjectTemplate',
  'repositories',
  'setupCommand',
  'phase',
  'collectArtifacts',
  // ADR 0064: agent tools are connectors of the agent
  'agentTools',
];

/** The coding agent keys a template still sets (ADR 0063). */
export const TEMPLATE_CODING_KEYS = [
  'codingAgent',
  'verifyCommand',
  'allowQuestions',
  'maxQuestionRounds',
];

/** Translated label and help of the known config keys (the server's English text is the fallback). */
function knownKeys(): Record<string, JsonSchema> {
  return {
    codingAgent: {
      type: 'boolean',
      title: $localize`:@@workflows.config.codingAgent:Works in the repository (needs a coding agent)`,
      description: $localize`:@@workflows.config.codingAgent.help:The work reads and changes files of the run directory, so a coding agent (OpenCode, Claude Code) must run it. Off: any agent, also a model answer.`,
      'x-widget': 'checkbox',
      'x-order': -1,
    },
    verifyCommand: {
      type: 'string',
      title: $localize`:@@workflows.config.verifyCommand:Verify command`,
      description: $localize`:@@workflows.config.verifyCommand.help:The acceptance check of the work, run after the agent, e.g. ./gradlew test. A failure fails the step.`,
      'x-multiline': false,
    },
    allowQuestions: {
      type: 'boolean',
      title: $localize`:@@workflows.config.allowQuestions:Agent may ask questions`,
      description: $localize`:@@workflows.config.allowQuestions.help:The agent may stop and ask the owner; the run waits for the answers.`,
    },
    maxQuestionRounds: {
      type: 'integer',
      title: $localize`:@@workflows.config.maxQuestionRounds:Question rounds`,
      description: $localize`:@@workflows.config.maxQuestionRounds.help:How many times the agent may ask before it has to finish.`,
    },
  };
}

/**
 * The schema with the translated labels of the known keys (type, default and limits stay the server's), without
 * the given keys; null when no property is left.
 */
export function localizedConfigSchema(
  schema: JsonSchema | null | undefined,
  without: readonly string[] = [],
): JsonSchema | null {
  if (!schema?.properties) return null;
  const known = knownKeys();
  const properties: Record<string, JsonSchema> = {};
  for (const [k, p] of Object.entries(schema.properties)) {
    if (without.includes(k)) continue;
    const t = known[k];
    properties[k] = t
      ? {
          ...p,
          title: t.title,
          description: t.description,
          ...(t['x-widget'] ? { 'x-widget': t['x-widget'] } : {}),
          ...(t['x-order'] !== undefined ? { 'x-order': t['x-order'] } : {}),
          ...(t['x-multiline'] !== undefined ? { 'x-multiline': t['x-multiline'] } : {}),
        }
      : p;
  }
  return Object.keys(properties).length ? { ...schema, properties } : null;
}

/**
 * The config form of a template (ADR 0063): the model answer keys and, of the coding agent keys, only those a
 * template still sets; `codingAgent` is always offered (a checkbox). `required` is dropped: a template only sets
 * what it changes. Null when the schemas are not known.
 */
export function templateFormSchema(
  modelAnswer: JsonSchema | null | undefined,
  coding: JsonSchema | null | undefined,
): JsonSchema | null {
  const codingProps = coding?.properties ?? {};
  const properties: Record<string, JsonSchema> = {};
  for (const [k, p] of Object.entries(modelAnswer?.properties ?? {}))
    if (!REMOVED_STEP_KEYS.includes(k)) properties[k] = p;
  for (const k of TEMPLATE_CODING_KEYS) {
    const p = codingProps[k] ?? properties[k];
    if (p) properties[k] = p;
  }
  if (!Object.keys(properties).length) return null;
  if (!properties['codingAgent']) properties['codingAgent'] = { type: 'boolean', default: false };
  const copy: JsonSchema = { ...(modelAnswer ?? {}), ...(coding ?? {}), properties };
  delete copy.required;
  return localizedConfigSchema(copy);
}
