import type {
  ModelProfile,
  Provider,
  ProviderCatalogSection,
  ProviderModelInfo,
} from '../../../core/api/types';

/** One model a connected provider offers (ADR 0045), flattened from the catalogue sections. */
export interface CatalogRow {
  /** `connection|modelId`, or `env:PROVIDER|modelId` for an environment endpoint. */
  key: string;
  connection: string | null;
  connectionName: string;
  provider: Provider;
  model: ProviderModelInfo;
  /** ADR 0050 step 2b: the caller's own connection; a profile of this model is personal. */
  personal: boolean;
}

export interface CatalogFilter {
  query?: string | null;
  connection?: string | null;
  freeOnly?: boolean;
}

/** Same key for a catalogue row and the profile created from it, so added models can be marked. */
export function catalogKey(
  provider: Provider,
  connection: string | null | undefined,
  modelId: string,
): string {
  return `${connection || 'env:' + provider}|${modelId}`;
}

export function catalogRows(sections: ProviderCatalogSection[]): CatalogRow[] {
  return sections.flatMap((s) =>
    (s.models ?? []).map((model) => ({
      key: catalogKey(s.provider, s.connection, model.id),
      connection: s.connection ?? null,
      connectionName: s.name,
      provider: s.provider,
      model,
      personal: !!s.personal,
    })),
  );
}

/** Rows matching the search (id or name), the connection and the free-only switch. */
export function filterCatalog(rows: CatalogRow[], f: CatalogFilter): CatalogRow[] {
  const q = (f.query ?? '').trim().toLowerCase();
  return rows.filter(
    (r) =>
      (!f.connection || (r.connection ?? 'env:' + r.provider) === f.connection) &&
      (!f.freeOnly || r.model.free) &&
      (!q ||
        r.model.id.toLowerCase().includes(q) ||
        (r.model.name ?? '').toLowerCase().includes(q)),
  );
}

/** ADR 0048: whether the profile runs Claude Code on the user's personal runner with their own Claude login. */
export function isLoginProfile(p: Pick<ModelProfile, 'runtime'> | null | undefined): boolean {
  return p?.runtime === 'CLAUDE_CODE_LOGIN';
}

/** Catalogue key of a profile: login profiles have their own namespace, apart from the API connections. */
export function profileKey(p: Pick<ModelProfile, 'ref' | 'runtime'>): string {
  return isLoginProfile(p)
    ? loginKey(p.ref.modelId)
    : catalogKey(p.ref.provider, p.ref.connection, p.ref.modelId);
}

export function loginKey(modelId: string): string {
  return `login|${modelId}`;
}

/** ADR 0048: the models Claude Code takes as `--model` aliases, offered as "Claude Code (your login)". */
export interface LoginModel {
  alias: 'opus' | 'sonnet' | 'haiku';
  displayName: string;
}

export function loginModels(): LoginModel[] {
  return [
    {
      alias: 'opus',
      displayName: $localize`:@@models.login.opus:Claude Opus via Claude Code (my login)`,
    },
    {
      alias: 'sonnet',
      displayName: $localize`:@@models.login.sonnet:Claude Sonnet via Claude Code (my login)`,
    },
    {
      alias: 'haiku',
      displayName: $localize`:@@models.login.haiku:Claude Haiku via Claude Code (my login)`,
    },
  ];
}

/** A profile without id for a Claude Code login model: Anthropic, no connection, nothing billed, coding only. */
export function profileFromLogin(m: LoginModel): ModelProfile {
  return {
    id: undefined as unknown as string,
    displayName: m.displayName,
    runtime: 'CLAUDE_CODE_LOGIN',
    ref: { provider: 'ANTHROPIC', modelId: m.alias, connection: null },
    pricing: { inputPerMTok: 0, outputPerMTok: 0, currency: 'USD' },
    capabilities: ['CODING'],
    contextWindow: null,
    notes: '',
    enabled: true,
    // ADR 0050 step 2: a Claude Code login profile is always personal
    personal: true,
  };
}

/**
 * A profile without id, prefilled from a catalogue row, for the profile drawer: provider, connection, model id, name,
 * the provider's prices (0 when unknown, with a note) and context window.
 */
export function profileFromCatalog(row: CatalogRow): ModelProfile {
  const m = row.model;
  const priced = m.inputPerMTok != null && m.outputPerMTok != null;
  const capabilities: NonNullable<ModelProfile['capabilities']> = [];
  if (m.free) capabilities.push('CHEAP');
  if ((m.contextWindow ?? 0) >= 200_000) capabilities.push('LONG_CONTEXT');
  return {
    id: undefined as unknown as string,
    displayName: m.name || m.id,
    ref: { provider: row.provider, modelId: m.id, connection: row.connection },
    pricing: {
      inputPerMTok: m.inputPerMTok ?? 0,
      outputPerMTok: m.outputPerMTok ?? 0,
      currency: 'USD',
    },
    capabilities,
    contextWindow: m.contextWindow ?? null,
    notes:
      priced || m.free
        ? ''
        : $localize`:@@models.catalog.priceUnknown:The provider does not publish prices; set them before trusting cost figures.`,
    enabled: true,
    // the caller's own connection: personal (ADR 0050 step 2b); otherwise the drawer decides (platform for managers)
    personal: row.personal,
  };
}
