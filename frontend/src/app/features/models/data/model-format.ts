/** Labels and filters of the model catalogue (pure; tested in model-format.spec.ts). */
import type { ModelProfile } from '../../../core/api/types';
import { formatTokens } from '../../../core/ui/pipes/tokens.pipe';
import { humanize } from '../../../core/ui/status-tag.component';
import { fixed } from '../../../core/i18n/numbers';

const PROVIDER_LABELS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  GOOGLE: 'Google',
  MISTRAL: 'Mistral',
  OLLAMA: 'Ollama',
  OPENAI_COMPATIBLE: $localize`:@@models.provider.openaiCompatible:OpenAI-compatible`,
};

/** "OpenAI", "OpenAI-compatible" (brand casing), unknown values in sentence case. */
export function providerLabel(provider: string | null | undefined): string {
  if (!provider) return '-';
  return (
    PROVIDER_LABELS[provider] ??
    provider
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/^\w/, (c) => c.toUpperCase())
  );
}

const REASONING_LABELS: Record<string, string> = {
  NONE: $localize`:@@models.reasoning.none:None`,
  LOW: $localize`:@@models.reasoning.low:Low`,
  MEDIUM: $localize`:@@models.reasoning.medium:Medium`,
  HIGH: $localize`:@@models.reasoning.high:High`,
  XHIGH: $localize`:@@models.reasoning.xhigh:Extra high`,
  MAX: $localize`:@@models.reasoning.max:Max`,
};

/** Reasoning effort in the UI language ("High"). */
export function reasoningLabel(value: string | null | undefined): string {
  return (value && REASONING_LABELS[value]) || humanize(value);
}

const CAPABILITY_LABELS: Record<string, string> = {
  CODING: $localize`:@@models.capability.coding:Coding`,
  REASONING: $localize`:@@models.capability.reasoning:Reasoning`,
  LONG_CONTEXT: $localize`:@@models.capability.longContext:Long context`,
  TOOLS: $localize`:@@models.capability.tools:Tools`,
  VISION: $localize`:@@models.capability.vision:Vision`,
  CHEAP: $localize`:@@models.capability.cheap:Cheap`,
};

/** Capability tag in the UI language ("Long context"). */
export function capabilityLabel(value: string | null | undefined): string {
  return (value && CAPABILITY_LABELS[value]) || humanize(value);
}

/** "$10.00" per million tokens (two decimals; the catalogue lists prices, not costs). */
export function perMTok(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-';
  return '$' + fixed(value, 2);
}

/** "200k", "1M" context window; "-" when unknown. */
export function contextLabel(tokens: number | null | undefined): string {
  if (!tokens) return '-';
  return formatTokens(tokens);
}

export type ProfileState = 'enabled' | 'disabled';

/** Profiles matching the search (name, model id, notes), provider and enabled state. */
export function filterProfiles(
  list: readonly ModelProfile[],
  opts: { query?: string | null; provider?: string | null; state?: string | null },
): ModelProfile[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return list.filter((p) => {
    if (opts.provider && p.ref.provider !== opts.provider) return false;
    if (opts.state === 'enabled' && p.enabled === false) return false;
    if (opts.state === 'disabled' && p.enabled !== false) return false;
    if (!q) return true;
    return [p.displayName, p.ref.modelId, p.notes ?? '', providerLabel(p.ref.provider)].some((f) =>
      f.toLowerCase().includes(q),
    );
  });
}
