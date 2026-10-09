/**
 * Maps the `errors[{field, message}]` extension of an RFC 9457 problem onto reactive form controls
 * (port of the reference `fieldMessages.ts`). Messages for fields the form does not render are
 * returned as `unmatched` so they can be shown in the notification instead of disappearing.
 */
import { HttpErrorResponse } from '@angular/common/http';
import type { AbstractControl } from '@angular/forms';
import type { Problem } from '../api/types';

export interface FieldMessage {
  field: string;
  message: string;
}

export interface ServerErrorResult {
  /** Title/detail of the problem (or a fallback). */
  message: string;
  /** Field paths that were set on a control (`control.errors.server`). */
  applied: string[];
  /** Field messages without a control on this form. */
  unmatched: FieldMessage[];
}

export function problemOf(err: unknown): Problem | null {
  if (err instanceof HttpErrorResponse && err.error && typeof err.error === 'object') {
    return err.error as Problem;
  }
  return null;
}

/** `projectMemberships[0].role` -> `projectMemberships.0.role` (Angular control path). */
export function toControlPath(field: string): string {
  return field.replace(/\[(\d+)\]/g, '.$1').replace(/^\./, '');
}

/**
 * Sets `{ server: message }` on the controls named by the problem's field errors and marks them
 * touched. `fieldMap` renames server fields to control paths where they differ.
 */
export function applyServerErrors(
  form: AbstractControl | null,
  err: unknown,
  fieldMap: Record<string, string> = {},
): ServerErrorResult {
  const problem = problemOf(err);
  const applied: string[] = [];
  const unmatched: FieldMessage[] = [];
  for (const e of problem?.errors ?? []) {
    const field = e.field ?? '';
    const message = e.message ?? $localize`:@@core.form.invalidValue:Invalid value`;
    const control = field && form ? form.get(fieldMap[field] ?? toControlPath(field)) : null;
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: message });
      control.markAsTouched();
      control.markAsDirty();
      applied.push(field);
    } else {
      unmatched.push({ field, message });
    }
  }
  const status = err instanceof HttpErrorResponse ? err.status : 0;
  const message =
    problem?.detail ||
    problem?.title ||
    (status
      ? $localize`:@@core.http.requestFailedStatus:Request failed (${status}:status:)`
      : $localize`:@@core.http.requestFailed:Request failed`);
  return { message, applied, unmatched };
}

/** Text for the notification: the problem detail plus every field message the form cannot show. */
export function summarize(result: ServerErrorResult): string {
  if (!result.unmatched.length) return result.message;
  const fields = result.unmatched
    .map((u) => (u.field ? `${u.field}: ${u.message}` : u.message))
    .join('; ');
  return `${result.message} (${fields})`;
}

/** Server message of a control, for `nzErrorTip`. */
export function serverError(control: AbstractControl | null | undefined): string | null {
  const value = control?.errors?.['server'];
  return typeof value === 'string' ? value : null;
}

/** Statuses the HTTP interceptor already reported (session, connection, access). */
const HANDLED_GLOBALLY = new Set([0, 401, 403]);

export interface ErrorNotifier {
  error(title: string, detail?: string): void;
}

/**
 * Standard handling of a failed save on a form: field errors go onto the controls, the rest into
 * one error notification. Errors the interceptor already reported are not repeated.
 */
export function reportFormError(
  notify: ErrorNotifier,
  form: AbstractControl | null,
  err: unknown,
  title = $localize`:@@core.form.couldNotSave:Could not save`,
  fieldMap: Record<string, string> = {},
): ServerErrorResult {
  const result = applyServerErrors(form, err, fieldMap);
  const status = err instanceof HttpErrorResponse ? err.status : -1;
  if (!HANDLED_GLOBALLY.has(status)) notify.error(title, summarize(result));
  return result;
}
