import type { LlmProviderInput, Provider } from '../../../core/api/types';

/** ADR 0045: provider connections added in Back Office. */
export const PROVIDER_TYPES: Provider[] = [
  'ANTHROPIC',
  'OPENAI',
  'GOOGLE',
  'MISTRAL',
  'OPENAI_COMPATIBLE',
  'OLLAMA',
];

export const CONNECTION_KEY_PATTERN = /^[a-z][a-z0-9-]{1,39}$/;

export interface ConnectionPreset {
  id: string;
  label: string;
  provider: Provider;
  baseUrl: string | null;
  /** ADR 0046: limits of the provider's free tier, prefilled (editable). */
  requestsPerMinute?: number;
  requestsPerDay?: number;
}

/** Common endpoints, filled in with one click (the API key still has to be pasted). */
export const PRESETS: ConnectionPreset[] = [
  // free models without purchased credits: 20 requests a minute, 50 a day (1000 a day after buying 10 credits)
  {
    id: 'openrouter',
    label: 'OpenRouter', // i18n-ignore: vendor name
    provider: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://openrouter.ai/api/v1',
    requestsPerMinute: 20,
    requestsPerDay: 50,
  },
  {
    id: 'groq',
    label: 'Groq', // i18n-ignore: vendor name
    provider: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.groq.com/openai/v1',
  },
  {
    id: 'github-models',
    label: 'GitHub Models', // i18n-ignore: vendor name
    provider: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://models.github.ai/inference',
  },
  { id: 'mistral', label: 'Mistral', provider: 'MISTRAL', baseUrl: null }, // i18n-ignore: vendor name
  { id: 'gemini', label: 'Google Gemini', provider: 'GOOGLE', baseUrl: null }, // i18n-ignore: vendor name
  { id: 'ollama', label: 'Ollama', provider: 'OLLAMA', baseUrl: 'http://localhost:11434' }, // i18n-ignore: vendor name
  {
    id: 'lm-studio',
    label: 'LM Studio', // i18n-ignore: vendor name
    provider: 'OPENAI_COMPATIBLE',
    baseUrl: 'http://localhost:1234/v1',
  },
];

export function emptyConnection(): LlmProviderInput {
  return {
    key: '',
    name: '',
    provider: 'OPENAI_COMPATIBLE',
    baseUrl: null,
    apiKey: null,
    clearApiKey: false,
    enabled: true,
    requestsPerMinute: null,
    requestsPerDay: null,
  };
}

/** What blocks saving; [creating] = a new connection (an API key is then expected where the type needs one). */
export function connectionProblems(
  input: LlmProviderInput,
  otherKeys: readonly string[],
  creating: boolean,
): string[] {
  const out: string[] = [];
  if (!CONNECTION_KEY_PATTERN.test(input.key ?? ''))
    out.push(
      $localize`:@@models.providers.problem.key:Key: 2-40 lower case letters, digits or hyphens, starting with a letter.`,
    );
  else if (otherKeys.includes(input.key))
    out.push($localize`:@@models.providers.problem.duplicate:Another connection has this key.`);
  if (!input.name?.trim()) out.push($localize`:@@models.providers.problem.name:Name is required.`);
  const url = input.baseUrl?.trim();
  if ((input.provider === 'OPENAI_COMPATIBLE' || input.provider === 'OLLAMA') && !url)
    out.push($localize`:@@models.providers.problem.url:This type needs a base URL.`);
  if (url && !/^https?:\/\//.test(url))
    out.push(
      $localize`:@@models.providers.problem.urlFormat:The base URL must start with http:// or https://.`,
    );
  if (creating && input.provider !== 'OLLAMA' && !input.apiKey?.trim())
    out.push($localize`:@@models.providers.problem.apiKey:Paste the API key of this provider.`);
  if (isSubscriptionToken(input)) out.push(SUBSCRIPTION_TOKEN_PROBLEM());
  return out;
}

/** ADR 0048: a Claude subscription (OAuth) token pasted where an Anthropic API key belongs. */
export function isSubscriptionToken(input: Pick<LlmProviderInput, 'provider' | 'apiKey'>): boolean {
  return input.provider === 'ANTHROPIC' && !!input.apiKey?.trim().startsWith('sk-ant-oat');
}

const SUBSCRIPTION_TOKEN_PROBLEM = () =>
  $localize`:@@models.providers.problem.subscriptionToken:This is a Claude Code subscription token, not an API key: the API does not accept it. Create an API key in the Anthropic Console, or use your subscription through a personal runner with Claude Code login (My account) and a "Claude Code (your login)" model.`;
