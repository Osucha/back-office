/**
 * Run start form (ADR 0053), pure helpers: the fields come from the run inputs the workflow's task steps
 * declare; the values become `parameters` by name and an ISSUE_KEY value is also the run's `issueKey`.
 * "Advanced (JSON)" adds extra parameters only: the issue key and declared fields are set in the form.
 */
import type { RunInputField, StartRunRequest } from '../../../core/api/types';

/** Longest value the server accepts for one field (RunInputField.MAX_LENGTH in the backend). */
export const MAX_RUN_INPUT_LENGTH = 20000;

export type RunInputError = 'required' | 'tooLong';

export type AdvancedError =
  | { kind: 'json'; detail: string }
  | { kind: 'notObject' }
  | { kind: 'issueKey' }
  | { kind: 'declared'; name: string };

export interface StartRequestResult {
  /** Undefined while a field or the advanced JSON is invalid. */
  request?: StartRunRequest;
  fieldErrors: Record<string, RunInputError>;
  advancedError?: AdvancedError;
}

/** Blank required fields and values over [MAX_RUN_INPUT_LENGTH], by field name. */
export function runInputErrors(
  fields: readonly RunInputField[],
  values: Readonly<Partial<Record<string, string>>>,
): Record<string, RunInputError> {
  const errors: Record<string, RunInputError> = {};
  for (const f of fields) {
    const value = values[f.name] ?? '';
    if (f.required && !value.trim()) errors[f.name] = 'required';
    else if (value.length > MAX_RUN_INPUT_LENGTH) errors[f.name] = 'tooLong';
  }
  return errors;
}

/** The advanced JSON as an object of extra parameters; blank means none. */
export function parseAdvanced(
  text: string,
  fields: readonly RunInputField[],
): { params?: Record<string, unknown>; error?: AdvancedError } {
  if (!text.trim()) return { params: {} };
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    return { error: { kind: 'json', detail: (e as Error).message } };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { error: { kind: 'notObject' } };
  }
  const params = parsed as Record<string, unknown>;
  const issueNames = new Set([
    'issueKey',
    ...fields.filter((f) => f.type === 'ISSUE_KEY').map((f) => f.name),
  ]);
  if (Object.keys(params).some((k) => issueNames.has(k))) return { error: { kind: 'issueKey' } };
  const declared = Object.keys(params).find((k) => fields.some((f) => f.name === k));
  if (declared) return { error: { kind: 'declared', name: declared } };
  return { params };
}

/** The start request of the form, or the errors that stop it. */
export function buildStartRequest(
  fields: readonly RunInputField[],
  values: Readonly<Partial<Record<string, string>>>,
  advancedText: string,
): StartRequestResult {
  const fieldErrors = runInputErrors(fields, values);
  const advanced = parseAdvanced(advancedText, fields);
  if (Object.keys(fieldErrors).length || advanced.error) {
    return { fieldErrors, advancedError: advanced.error };
  }
  const parameters: Record<string, unknown> = { ...advanced.params };
  let issueKey: string | null = null;
  for (const f of fields) {
    const value = (values[f.name] ?? '').trim();
    if (!value) continue;
    // a multi-line text keeps its own formatting; single-line values are trimmed
    parameters[f.name] = f.type === 'MULTILINE' ? values[f.name] : value;
    if (f.type === 'ISSUE_KEY' && issueKey === null) issueKey = value;
  }
  return { request: { issueKey, parameters }, fieldErrors };
}
