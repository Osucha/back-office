import type { LlmProviderInput, Provider } from '../../../core/api/types';

/** ADR 0045: provider connections added in Back Office. */
export const PROVIDER_TYPES: Provider[] = ['ANTHROPIC', 'OPENAI', 'GOOGLE', 'MISTRAL', 'OPENAI_COMPATIBLE', 'OLLAMA'];

export const CONNECTION_KEY_PATTERN = /^[a-z][a-z0-9-]{1,39}$/;

export interface ConnectionPreset {
  id: string;
  label: string;
  provider: Provider;
  baseUrl: string | null;
}

/** Common endpoints, filled in with one click (the API key still has to be pasted). */
export const PRESETS: ConnectionPreset[] = [
  { id: 'openrouter', label: 'OpenRouter', provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://openrouter.ai/api/v1' }, // i18n-ignore: vendor name
  { id: 'groq', label: 'Groq', provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://api.groq.com/openai/v1' }, // i18n-ignore: vendor name
  { id: 'github-models', label: 'GitHub Models', provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://models.github.ai/inference' }, // i18n-ignore: vendor name
  { id: 'mistral', label: 'Mistral', provider: 'MISTRAL', baseUrl: null }, // i18n-ignore: vendor name
  { id: 'gemini', label: 'Google Gemini', provider: 'GOOGLE', baseUrl: null }, // i18n-ignore: vendor name
  { id: 'ollama', label: 'Ollama', provider: 'OLLAMA', baseUrl: 'http://localhost:11434' }, // i18n-ignore: vendor name
  { id: 'lm-studio', label: 'LM Studio', provider: 'OPENAI_COMPATIBLE', baseUrl: 'http://localhost:1234/v1' }, // i18n-ignore: vendor name
];

export function emptyConnection(): LlmProviderInput {
  return { key: '', name: '', provider: 'OPENAI_COMPATIBLE', baseUrl: null, apiKey: null, clearApiKey: false, enabled: true };
}

/** What blocks saving; [creating] = a new connection (an API key is then expected where the type needs one). */
export function connectionProblems(input: LlmProviderInput, otherKeys: readonly string[], creating: boolean): string[] {
  const out: string[] = [];
  if (!CONNECTION_KEY_PATTERN.test(input.key ?? ''))
    out.push($localize`:@@models.providers.problem.key:Key: 2-40 lower case letters, digits or hyphens, starting with a letter.`);
  else if (otherKeys.includes(input.key)) out.push($localize`:@@models.providers.problem.duplicate:Another connection has this key.`);
  if (!input.name?.trim()) out.push($localize`:@@models.providers.problem.name:Name is required.`);
  const url = input.baseUrl?.trim();
  if ((input.provider === 'OPENAI_COMPATIBLE' || input.provider === 'OLLAMA') && !url)
    out.push($localize`:@@models.providers.problem.url:This type needs a base URL.`);
  if (url && !/^https?:\/\//.test(url)) out.push($localize`:@@models.providers.problem.urlFormat:The base URL must start with http:// or https://.`);
  if (creating && input.provider !== 'OLLAMA' && !input.apiKey?.trim())
    out.push($localize`:@@models.providers.problem.apiKey:Paste the API key of this provider.`);
  return out;
}
